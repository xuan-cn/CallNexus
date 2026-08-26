package org.dromara.ai.realtime;

import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.domain.event.StreamingAsrTranscriptEvent;
import org.dromara.ai.provider.AsrSegment;
import org.dromara.ai.provider.AsrTranscribeResult;
import org.dromara.ai.provider.StreamingAsrListener;
import org.dromara.ai.provider.StreamingAsrProviderRegistry;
import org.dromara.ai.provider.StreamingAsrRequest;
import org.dromara.ai.provider.StreamingAsrSession;
import org.dromara.ai.service.AiSpeechProviderSelector;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class StreamingAsrSessionManager {
    private static final int MAX_PENDING_AUDIO_CHUNKS = 64;

    private final StreamingAsrTokenService tokenService;
    private final AiSpeechProviderSelector speechProviderSelector;
    private final StreamingAsrProviderRegistry streamingAsrRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final Executor executor;
    private final Map<String, RuntimeSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> legSessions = new ConcurrentHashMap<>();

    public StreamingAsrSessionManager(StreamingAsrTokenService tokenService,
                                      AiSpeechProviderSelector speechProviderSelector,
                                      StreamingAsrProviderRegistry streamingAsrRegistry,
                                      ApplicationEventPublisher eventPublisher,
                                      @Qualifier("aiRealtimeExecutor") Executor executor) {
        this.tokenService = tokenService;
        this.speechProviderSelector = speechProviderSelector;
        this.streamingAsrRegistry = streamingAsrRegistry;
        this.eventPublisher = eventPublisher;
        this.executor = executor;
    }

    public void connect(WebSocketSession socket, String token) {
        StreamingAsrClaims claims = tokenService.verify(token);
        requireClaims(claims);
        RuntimeSession runtime = TenantHelper.dynamic(claims.tenantId(), () -> createRuntime(socket, claims));
        String legKey = legKey(claims);
        if (legSessions.putIfAbsent(legKey, socket.getId()) != null) {
            throw new ServiceException("该电话腿已存在流式 ASR 连接");
        }
        if (sessions.putIfAbsent(socket.getId(), runtime) != null) {
            legSessions.remove(legKey, socket.getId());
            throw new ServiceException("流式 ASR 音频会话重复");
        }
        executor.execute(() -> TenantHelper.dynamic(claims.tenantId(), () -> start(runtime)));
    }

    public void audio(String socketId, byte[] audioBytes) {
        RuntimeSession runtime = sessions.get(socketId);
        if (runtime != null) {
            runtime.send(audioBytes);
        }
    }

    public void fail(String socketId, String reason) {
        RuntimeSession runtime = sessions.get(socketId);
        if (runtime != null) {
            log.warn("流式 ASR 音频会话异常，businessCallId={}，legUuid={}，speaker={}，error={}",
                runtime.claims.businessCallId(), runtime.claims.legUuid(), runtime.claims.speaker(), reason);
        }
    }

    public void disconnect(String socketId, String reason) {
        RuntimeSession runtime = sessions.remove(socketId);
        if (runtime == null) {
            return;
        }
        legSessions.remove(legKey(runtime.claims), socketId);
        runtime.close();
        log.info("流式 ASR 音频会话结束，businessCallId={}，legUuid={}，speaker={}，reason={}",
            runtime.claims.businessCallId(), runtime.claims.legUuid(), runtime.claims.speaker(), reason);
    }

    private RuntimeSession createRuntime(WebSocketSession socket, StreamingAsrClaims claims) {
        AiSpeechProvider provider = speechProviderSelector.requireDefaultStreamingAsr();
        return new RuntimeSession(socket, claims, provider);
    }

    private void start(RuntimeSession runtime) {
        try {
            StreamingAsrSession asrSession = streamingAsrRegistry.get(runtime.provider.getProviderType()).open(
                runtime.provider,
                new StreamingAsrRequest("pcm", 16000, runtime.provider.getAsrLanguage(), Map.of(
                    "businessCallId", runtime.claims.businessCallId(),
                    "legUuid", runtime.claims.legUuid(),
                    "speaker", runtime.claims.speaker())),
                new StreamingAsrListener() {
                    @Override
                    public void onResult(AsrSegment segment) {
                        if (segment.finalResult() && StringUtils.isNotBlank(segment.text())) {
                            publish(runtime, segment);
                        }
                    }

                    @Override
                    public void onCompleted(AsrTranscribeResult result) {
                        log.debug("流式 ASR 已完成，businessCallId={}，legUuid={}，text={}",
                            runtime.claims.businessCallId(), runtime.claims.legUuid(), result.fullText());
                    }

                    @Override
                    public void onError(String message) {
                        fail(runtime.socket.getId(), message);
                        abort(runtime, "provider-error");
                    }
                });
            runtime.attach(asrSession);
            log.info("流式 ASR 音频会话建立，businessCallId={}，legUuid={}，speaker={}，provider={}",
                runtime.claims.businessCallId(), runtime.claims.legUuid(), runtime.claims.speaker(),
                runtime.provider.getProviderType());
        } catch (Exception exception) {
            fail(runtime.socket.getId(), exception.getMessage());
            abort(runtime, "provider-start-failed");
        }
    }

    private void abort(RuntimeSession runtime, String reason) {
        executor.execute(() -> {
            disconnect(runtime.socket.getId(), reason);
            if (runtime.socket.isOpen()) {
                try {
                    runtime.socket.close(CloseStatus.SERVER_ERROR.withReason(reason));
                } catch (IOException exception) {
                    log.debug("关闭流式 ASR WebSocket 失败，socketId={}，error={}",
                        runtime.socket.getId(), exception.getMessage());
                }
            }
        });
    }

    private void publish(RuntimeSession runtime, AsrSegment segment) {
        executor.execute(() -> TenantHelper.dynamic(runtime.claims.tenantId(), () -> eventPublisher.publishEvent(
            new StreamingAsrTranscriptEvent(runtime.claims.tenantId(), runtime.claims.nodeId(),
                runtime.claims.businessCallId(), runtime.claims.legUuid(), runtime.claims.speaker(),
                runtime.provider.getProviderType(), segment))));
    }

    private void requireClaims(StreamingAsrClaims claims) {
        if (StringUtils.isBlank(claims.tenantId()) || claims.nodeId() == null
            || StringUtils.isBlank(claims.businessCallId()) || StringUtils.isBlank(claims.legUuid())
            || !("CUSTOMER".equals(claims.speaker()) || "AGENT".equals(claims.speaker()))) {
            throw new ServiceException("流式 ASR 令牌缺少有效通话腿信息");
        }
    }

    private String legKey(StreamingAsrClaims claims) {
        return claims.tenantId() + ':' + claims.nodeId() + ':' + claims.legUuid();
    }

    private static final class RuntimeSession {
        private final WebSocketSession socket;
        private final StreamingAsrClaims claims;
        private final AiSpeechProvider provider;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Deque<byte[]> pendingAudio = new ArrayDeque<>();
        private StreamingAsrSession asrSession;

        private RuntimeSession(WebSocketSession socket, StreamingAsrClaims claims, AiSpeechProvider provider) {
            this.socket = socket;
            this.claims = claims;
            this.provider = provider;
        }

        private synchronized void send(byte[] audioBytes) {
            if (closed.get() || audioBytes == null || audioBytes.length == 0) {
                return;
            }
            if (asrSession == null) {
                if (pendingAudio.size() >= MAX_PENDING_AUDIO_CHUNKS) {
                    pendingAudio.removeFirst();
                }
                pendingAudio.addLast(audioBytes);
                return;
            }
            asrSession.send(audioBytes);
        }

        private synchronized void attach(StreamingAsrSession session) {
            if (closed.get()) {
                session.close();
                return;
            }
            asrSession = session;
            while (!pendingAudio.isEmpty()) {
                asrSession.send(pendingAudio.removeFirst());
            }
        }

        private synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            pendingAudio.clear();
            if (asrSession != null) {
                try {
                    asrSession.finish();
                } finally {
                    asrSession.close();
                }
            }
        }
    }
}
