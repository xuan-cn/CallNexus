package org.dromara.ai.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.request.AiRealtimeTtsRequest;
import org.dromara.ai.provider.StreamingTtsListener;
import org.dromara.ai.service.AiRealtimeTtsInternalService;
import org.dromara.ai.service.SentenceSegmenter;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UniMRCP callnexussynth 插件的 TTS 流式 WebSocket 端点。
 *
 * <p>协议（以 JSON 文本帧承载指令、二进制帧承载 PCM 音频）：
 * <pre>
 *   插件 → 服务端 (TEXT):
 *     {"type":"start", "tenantId":"...", "callId":"...", "turnId":"...", "voice":"...", "sampleRate":8000, "format":"pcm"}
 *     {"type":"text",  "seq":1, "text":"这一段马上要说的文本"}   // 服务端每段合成后即回推 BINARY
 *     {"type":"flush", "lastSeq":3}                              // 本轮文本发送完毕
 *     {"type":"cancel"}                                          // barge-in 立即停止
 *
 *   服务端 → 插件:
 *     BINARY(pcm 分段音频)
 *     {"type":"segmentEnd","bytes":...}                          // 段完成
 *     {"type":"completed"}                                       // 整轮完成，随后服务端主动 Close 1000
 *     {"type":"done"}                                            // 兼容旧插件，与 completed 同时发送
 *     {"type":"cancelled"}                                       // 已取消，随后 Close 1000
 *     {"type":"error","message":"..."}
 * </pre>
 *
 * <p>与旧版的关键差异：
 * <ul>
 *   <li>{@code text} 不再在 WS IO 线程内同步合成，而是入队后由 {@code aiRealtimeExecutor}
 *       单线程串行消费，避免阻塞 Undertow WS 读线程。</li>
 *   <li>本轮结束（收到 flush 且队列排空，或空闲兜底）后服务端主动完成 WebSocket
 *       Close 握手（1000），消除对端直接断 TCP 造成的 {@code code=1006}。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiRealtimeTtsStreamWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 段队列上限，超出说明生产远快于消费，拒绝并回 error。 */
    private static final int SEGMENT_QUEUE_CAPACITY = 64;
    /** 消费循环单次 poll 超时，用于周期性检查 flush/cancel/idle 状态。 */
    private static final long POLL_TIMEOUT_MS = 200L;
    /**
     * 无显式 flush 的兜底：合成完一段后，若在该时长内既无新 text 也无 flush，
     * 视为本轮结束，触发 completed + Close 1000。兼容"每次 SPEAK 只发单个 text 就断"的旧插件。
     */
    /** 空闲超时：超过该时长无任何消息则强制 Close 1000，防止连接泄漏。 */
    private static final long SESSION_IDLE_TIMEOUT_MS = 45_000L;

    private final AiRealtimeTtsInternalService ttsService;
    private final Executor consumeExecutor;
    private final ThreadPoolTaskScheduler scheduler;
    private final AiRealtimeTtsConnectionRegistry connectionRegistry;

    private final Map<String, StreamState> states = new ConcurrentHashMap<>();

    public AiRealtimeTtsStreamWebSocketHandler(AiRealtimeTtsInternalService ttsService,
                                               @Qualifier("aiRealtimeExecutor") Executor consumeExecutor,
                                               @Qualifier("aiRealtimeScheduler") ThreadPoolTaskScheduler scheduler,
                                               AiRealtimeTtsConnectionRegistry connectionRegistry) {
        this.ttsService = ttsService;
        this.consumeExecutor = consumeExecutor;
        this.scheduler = scheduler;
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 提高单帧文本/二进制上限，避免 Spring 默认 8192 引发 partial message 拆帧
        session.setTextMessageSizeLimit(65536);
        session.setBinaryMessageSizeLimit(65536);
        states.put(session.getId(), new StreamState());
        log.info("AI 实时 TTS WS 连接建立，sessionId={}，remote={}", session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        StreamState state = states.get(session.getId());
        if (state == null) {
            log.warn("AI 实时 TTS WS 未初始化会话即收到消息，sessionId={}", session.getId());
            return;
        }
        state.lastActivityMs.set(System.currentTimeMillis());
        String payload = message.getPayload();
        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (Exception exception) {
            log.warn("AI 实时 TTS WS 收到非法 JSON，已忽略不断连，sessionId={}，len={}，payload={}，error={}",
                session.getId(), payload == null ? 0 : payload.length(), payload, exception.getMessage());
            sendError(session, "非法 JSON：" + exception.getMessage());
            return;
        }
        String type = node.path("type").asText();
        switch (type) {
            case "start" -> handleStart(session, state, node);
            case "text" -> handleText(session, state, node);
            case "flush" -> handleFlush(session, state, node);
            case "cancel" -> handleCancel(session, state);
            default -> log.debug("AI 实时 TTS WS 忽略未知消息类型，sessionId={}，type={}", session.getId(), type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        StreamState removed = states.remove(session.getId());
        if (removed != null) {
            removed.cancelled.set(true);
            removed.consuming.set(false);
            cancelIdleTimer(removed);
            connectionRegistry.unregister(removed.callId, session.getId());
        }
        log.info("AI 实时 TTS WS 连接关闭，sessionId={}，status={}", session.getId(), status);
    }

    private void handleStart(WebSocketSession session, StreamState state, JsonNode node) {
        state.tenantId = node.path("tenantId").asText(null);
        state.callId = node.path("callId").asText(null);
        state.turnId = node.path("turnId").asText(null);
        state.voice = normalizeVoice(node.path("voice").asText(null));
        state.sampleRate = node.path("sampleRate").isNumber() ? node.path("sampleRate").asInt() : null;
        state.format = node.path("format").asText(null);
        state.segmenter = new SentenceSegmenter();
        state.cancelled.set(false);
        state.flushReceived.set(false);
        if (StringUtils.isBlank(state.tenantId)) {
            sendError(session, "start 缺少 tenantId");
            return;
        }
        connectionRegistry.register(state.callId, state.turnId, session.getId(),
            () -> cancelSession(session, state, "call ended", false));
        startConsumeLoop(session, state);
        scheduleIdleTimer(session, state);
        sendJson(session, Map.of("type", "started", "turnId", StringUtils.blankToDefault(state.turnId, "")));
        log.info("AI 实时 TTS WS 会话开始，sessionId={}，tenantId={}，callId={}，turnId={}，voice={}，format={}，sampleRate={}",
            session.getId(), state.tenantId, state.callId, state.turnId, state.voice, state.format, state.sampleRate);
    }

    private void handleText(WebSocketSession session, StreamState state, JsonNode node) {
        if (state.segmenter == null || StringUtils.isBlank(state.tenantId)) {
            sendError(session, "尚未收到 start");
            return;
        }
        // 兼容两种字段名：注释里的 "delta"，以及插件实际发送的 "text"。
        String delta = node.path("delta").asText("");
        if (StringUtils.isBlank(delta)) {
            delta = node.path("text").asText("");
        }
        if (StringUtils.isBlank(delta)) {
            return;
        }
        if (state.cancelled.get()) {
            return;
        }
        // ASR 识别结果透传场景：短语需立即合成，不进分句器。
        String text = normalizeText(delta);
        if (StringUtils.isBlank(text)) {
            return;
        }
        Integer seq = node.path("seq").isNumber() ? node.path("seq").asInt() : null;
        boolean accepted = state.segmentQueue.offer(new Segment(seq, text));
        if (!accepted) {
            sendError(session, "TTS 队列已满");
            log.warn("AI 实时 TTS WS 段队列已满，丢弃文本，sessionId={}", session.getId());
        }
    }

    private void handleFlush(WebSocketSession session, StreamState state, JsonNode node) {
        state.flushReceived.set(true);
        if (node.path("lastSeq").isNumber()) {
            state.lastSeq = node.path("lastSeq").asInt();
        }
        // 实际的 completed / Close 由消费循环在队列排空后统一处理，保证顺序正确。
        log.debug("AI 实时 TTS WS 收到 flush，sessionId={}", session.getId());
    }

    private void handleCancel(WebSocketSession session, StreamState state) {
        cancelSession(session, state, "cancelled", true);
    }

    private void cancelSession(WebSocketSession session, StreamState state, String reason, boolean notifyPeer) {
        state.cancelled.set(true);
        state.consuming.set(false);
        state.segmentQueue.clear();
        if (state.segmenter != null) {
            state.segmenter.drain();
        }
        connectionRegistry.unregister(state.callId, session.getId());
        log.info("AI 实时 TTS WS 已取消，sessionId={}，callId={}，reason={}",
            session.getId(), state.callId, reason);
        if (notifyPeer) {
            sendJson(session, Map.of("type", "cancelled"));
        }
        closeSession(session, state, new CloseStatus(1000, reason));
    }

    /**
     * 每条连接一个消费循环，串行从段队列取文本并合成，避免占用 WS IO 线程。
     * 循环在收到 flush 且队列排空、或无 flush 但空闲超过 {@link #IMPLICIT_FLUSH_IDLE_MS}
     * 时结束本轮，发送 completed 并主动 Close 1000。
     */
    private void startConsumeLoop(WebSocketSession session, StreamState state) {
        if (!state.consuming.compareAndSet(false, true)) {
            return;
        }
        consumeExecutor.execute(() -> TenantHelper.dynamic(state.tenantId, () -> {
            try {
                while (state.consuming.get() && !state.cancelled.get() && session.isOpen()) {
                    Segment segment = state.segmentQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (segment != null) {
                        synthesizeSegment(session, state, segment);
                        continue;
                    }
                    // 队列暂空。判断是否应结束本轮。
                    if (!state.segmentQueue.isEmpty()) {
                        continue;
                    }
                    if (state.flushReceived.get()) {
                        completeTurn(session, state, "flush");
                        return null;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                log.warn("AI 实时 TTS WS 消费循环异常，sessionId={}", session.getId(), exception);
                sendError(session, exception.getMessage());
                closeSession(session, state, new CloseStatus(1011, "tts synthesis failed"));
            }
            return null;
        }));
    }

    private void completeTurn(WebSocketSession session, StreamState state, String reason) {
        if (state.cancelled.get() || !session.isOpen()) {
            return;
        }
        state.consuming.set(false);
        sendJson(session, completedPayload(state));
        log.info("AI 实时 TTS WS 本轮完成，sessionId={}，callId={}，turnId={}，reason={}",
            session.getId(), state.callId, state.turnId, reason);
    }

    private Map<String, Object> completedPayload(StreamState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "completed");
        if (StringUtils.isNotBlank(state.turnId)) {
            payload.put("turnId", state.turnId);
        }
        if (state.lastSeq != null) {
            payload.put("lastSeq", state.lastSeq);
        }
        return payload;
    }

    private void sendSegmentStarted(WebSocketSession session, Segment segment) {
        if (segment.seq() == null) {
            return;
        }
        sendJson(session, Map.of("type", "segment_started", "seq", segment.seq()));
    }

    private void sendSegmentCompleted(WebSocketSession session, StreamState state, Segment segment, int bytes) {
        sendJson(session, Map.of("type", "segmentEnd", "bytes", bytes));
        if (segment.seq() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "segment_completed");
        payload.put("seq", segment.seq());
        payload.put("audioBytes", bytes);
        if (StringUtils.isNotBlank(state.turnId)) {
            payload.put("turnId", state.turnId);
        }
        sendJson(session, payload);
    }

    private void synthesizeSegment(WebSocketSession session, StreamState state, Segment segment) {
        String sentence = segment.text();
        if (StringUtils.isBlank(sentence) || state.cancelled.get()) {
            return;
        }
        long startNanos = System.nanoTime();
        AiRealtimeTtsRequest request = buildRequest(state, sentence);
        AiRealtimeTtsInternalService.RealtimeTtsAudio cached = ttsService.findCachedForStream(request);
        if (cached != null) {
            sendSegmentStarted(session, segment);
            try {
                synchronized (session) {
                    session.sendMessage(new BinaryMessage(ByteBuffer.wrap(cached.bytes())));
                }
                sendSegmentCompleted(session, state, segment, cached.bytes().length);
                log.info("AI 实时 TTS WS 命中预热缓存，sessionId={}，textLen={}，bytes={}，costMs={}",
                    session.getId(), sentence.length(), cached.bytes().length,
                    (System.nanoTime() - startNanos) / 1_000_000L);
            } catch (Exception exception) {
                log.warn("AI 实时 TTS WS 推送预热音频失败，sessionId={}，seq={}",
                    session.getId(), segment.seq(), exception);
                sendError(session, exception.getMessage());
            }
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger totalBytes = new AtomicInteger();
        AtomicBoolean firstChunk = new AtomicBoolean(true);
        AtomicBoolean fallbackTriggered = new AtomicBoolean();
        StreamingTtsListener listener = new StreamingTtsListener() {
            @Override
            public void onStarted() {
                sendSegmentStarted(session, segment);
                log.debug("AI 实时 TTS WS 流式合成开始，sessionId={}，textLen={}",
                    session.getId(), sentence.length());
            }

            @Override
            public void onAudio(byte[] chunk) {
                if (state.cancelled.get() || !session.isOpen() || chunk == null || chunk.length == 0) {
                    return;
                }
                try {
                    synchronized (session) {
                        session.sendMessage(new BinaryMessage(ByteBuffer.wrap(chunk)));
                    }
                    totalBytes.addAndGet(chunk.length);
                    if (firstChunk.compareAndSet(true, false)) {
                        log.info("AI 实时 TTS WS 首字节，sessionId={}，textLen={}，ttfbMs={}",
                            session.getId(), sentence.length(),
                            (System.nanoTime() - startNanos) / 1_000_000L);
                    }
                } catch (Exception exception) {
                    log.warn("AI 实时 TTS WS 推送 chunk 失败，sessionId={}", session.getId(), exception);
                }
            }

            @Override
            public void onCompleted() {
                try {
                if (state.cancelled.get() || !session.isOpen()) {
                    return;
                }
                sendSegmentCompleted(session, state, segment, totalBytes.get());
                log.info("AI 实时 TTS WS 段合成完成（stream），sessionId={}，textLen={}，bytes={}，costMs={}",
                    session.getId(), sentence.length(), totalBytes.get(),
                    (System.nanoTime() - startNanos) / 1_000_000L);
                } finally {
                    done.countDown();
                }
            }

            @Override
            public void onError(String message) {
                if (!fallbackTriggered.compareAndSet(false, true)) {
                    return;
                }
                log.warn("AI 实时 TTS WS 流式合成失败，将回退到 HTTP 一次性 TTS，sessionId={}，text={}，error={}",
                    session.getId(), sentence, message);
                fallbackHttp(session, state, segment);
                done.countDown();
            }
        };

        try {
            ttsService.generateStream(request, listener);
        } catch (Exception exception) {
            if (fallbackTriggered.compareAndSet(false, true)) {
                log.warn("AI 实时 TTS WS 流式入口异常，回退 HTTP，sessionId={}，text={}",
                    session.getId(), sentence, exception);
                fallbackHttp(session, state, segment);
            }
            done.countDown();
        }
        try {
            if (!done.await(30, TimeUnit.SECONDS)) {
                sendError(session, "TTS segment timeout");
                log.warn("AI 实时 TTS WS 段合成等待超时，sessionId={}，seq={}", session.getId(), segment.seq());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void fallbackHttp(WebSocketSession session, StreamState state, Segment segment) {
        String sentence = segment.text();
        if (state.cancelled.get() || !session.isOpen()) {
            return;
        }
        long startNanos = System.nanoTime();
        try {
            AiRealtimeTtsRequest request = buildRequest(state, sentence);
            AiRealtimeTtsInternalService.RealtimeTtsAudio audio = ttsService.generateForStream(request);
            if (state.cancelled.get() || !session.isOpen()) {
                return;
            }
            synchronized (session) {
                session.sendMessage(new BinaryMessage(ByteBuffer.wrap(audio.bytes())));
                sendSegmentCompleted(session, state, segment, audio.bytes().length);
            }
            log.info("AI 实时 TTS WS 段合成完成（http fallback），sessionId={}，textLen={}，bytes={}，costMs={}",
                session.getId(), sentence.length(), audio.bytes().length,
                (System.nanoTime() - startNanos) / 1_000_000L);
        } catch (Exception exception) {
            log.warn("AI 实时 TTS WS HTTP fallback 失败，sessionId={}，text={}",
                session.getId(), sentence, exception);
            String message = exception.getMessage();
            if (message == null) {
                message = exception.getClass().getName();
            }
            sendError(session, message);
        }
    }

    private AiRealtimeTtsRequest buildRequest(StreamState state, String sentence) {
        AiRealtimeTtsRequest request = new AiRealtimeTtsRequest();
        request.setTenantId(state.tenantId);
        request.setVoice(state.voice);
        request.setFormat(state.format);
        request.setSampleRate(state.sampleRate);
        request.setText(sentence);
        return request;
    }

    private String normalizeVoice(String voice) {
        if (StringUtils.isBlank(voice) || "default".equalsIgnoreCase(voice.trim())) {
            return null;
        }
        return voice.trim();
    }

    private String normalizeText(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String value = text.trim();
        if (value.startsWith("<")) {
            value = value
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private void scheduleIdleTimer(WebSocketSession session, StreamState state) {
        cancelIdleTimer(state);
        ScheduledFuture<?> handle = scheduler.scheduleWithFixedDelay(
            () -> TenantHelper.dynamic(state.tenantId, () -> {
                if (!session.isOpen()) {
                    cancelIdleTimer(state);
                    return;
                }
                long idle = System.currentTimeMillis() - state.lastActivityMs.get();
                if (idle >= SESSION_IDLE_TIMEOUT_MS) {
                    log.info("AI 实时 TTS WS 空闲超时，主动关闭，sessionId={}，idleMs={}", session.getId(), idle);
                    state.consuming.set(false);
                    closeSession(session, state, new CloseStatus(1000, "idle timeout"));
                }
            }),
            Instant.now().plus(Duration.ofMillis(SESSION_IDLE_TIMEOUT_MS)),
            Duration.ofMillis(SESSION_IDLE_TIMEOUT_MS / 3));
        state.idleTimer = handle;
    }

    private void cancelIdleTimer(StreamState state) {
        ScheduledFuture<?> timer = state.idleTimer;
        if (timer != null) {
            timer.cancel(false);
            state.idleTimer = null;
        }
    }

    private void closeSession(WebSocketSession session, StreamState state, CloseStatus status) {
        if (!state.closing.compareAndSet(false, true)) {
            return;
        }
        state.consuming.set(false);
        cancelIdleTimer(state);
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.close(status);
            }
        } catch (Exception exception) {
            log.debug("AI 实时 TTS WS 关闭异常，sessionId={}，error={}", session.getId(), exception.getMessage());
        }
    }

    private void sendJson(WebSocketSession session, Object value) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(MAPPER.writeValueAsString(value)));
            }
        } catch (Exception exception) {
            log.debug("AI 实时 TTS WS 发送 JSON 失败，sessionId={}，error={}", session.getId(), exception.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String message) {
        sendJson(session, Map.of("type", "error", "message", message == null ? "" : message));
    }

    private static final class StreamState {
        volatile String tenantId;
        volatile String callId;
        volatile String turnId;
        volatile String voice;
        volatile String format;
        volatile Integer sampleRate;
        volatile Integer lastSeq;
        volatile SentenceSegmenter segmenter;
        volatile ScheduledFuture<?> idleTimer;
        final BlockingQueue<Segment> segmentQueue = new LinkedBlockingQueue<>(SEGMENT_QUEUE_CAPACITY);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean flushReceived = new AtomicBoolean();
        final AtomicBoolean consuming = new AtomicBoolean();
        final AtomicBoolean closing = new AtomicBoolean();
        final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());
    }

    private record Segment(Integer seq, String text) {
    }
}
