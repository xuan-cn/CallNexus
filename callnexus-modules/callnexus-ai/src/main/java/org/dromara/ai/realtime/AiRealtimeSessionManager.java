package org.dromara.ai.realtime;

import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.domain.response.AiConversationStartResponse;
import org.dromara.ai.mapper.AiRealtimeCallSessionMapper;
import org.dromara.ai.mapper.AiRealtimeCallTurnMapper;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.ai.service.AiSpeechProviderSelector;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class AiRealtimeSessionManager {
    private final AiRealtimeTokenService tokenService;
    private final AiSpeechProviderSelector speechProviderSelector;
    private final StreamingAsrProviderRegistry streamingAsrRegistry;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final AiAgentApplicationService agentService;
    private final AiRealtimeCallSessionMapper sessionMapper;
    private final AiRealtimeCallTurnMapper turnMapper;
    private final Executor executor;
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<String, RuntimeSession> sessions = new ConcurrentHashMap<>();

    public AiRealtimeSessionManager(AiRealtimeTokenService tokenService,
                                    AiSpeechProviderSelector speechProviderSelector,
                                    StreamingAsrProviderRegistry streamingAsrRegistry,
                                    TtsProviderRegistry ttsProviderRegistry,
                                    AiAgentApplicationService agentService,
                                    AiRealtimeCallSessionMapper sessionMapper,
                                    AiRealtimeCallTurnMapper turnMapper,
                                    @Qualifier("aiRealtimeExecutor") Executor executor,
                                    @Qualifier("aiRealtimeScheduler") ThreadPoolTaskScheduler scheduler) {
        this.tokenService = tokenService;
        this.speechProviderSelector = speechProviderSelector;
        this.streamingAsrRegistry = streamingAsrRegistry;
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.agentService = agentService;
        this.sessionMapper = sessionMapper;
        this.turnMapper = turnMapper;
        this.executor = executor;
        this.scheduler = scheduler;
    }

    public void connect(WebSocketSession socket, String token, String businessCallId, String customerLegUuid) {
        AiRealtimeClaims claims = tokenService.verify(token);
        if (StringUtils.isBlank(businessCallId) || StringUtils.isBlank(customerLegUuid)) {
            throw new ServiceException("AI 实时音频连接缺少业务通话 ID 或客户腿 UUID");
        }
        RuntimeSession runtime = TenantHelper.dynamic(claims.tenantId(), () -> createRuntime(socket, claims,
            businessCallId, customerLegUuid));
        if (sessions.putIfAbsent(socket.getId(), runtime) != null) {
            runtime.close();
            throw new ServiceException("AI 实时音频会话重复");
        }
        executor.execute(() -> TenantHelper.dynamic(claims.tenantId(), () -> start(runtime)));
    }

    public void audio(String socketId, byte[] audioBytes) {
        RuntimeSession runtime = sessions.get(socketId);
        if (runtime != null && runtime.asrSession != null && !runtime.closed.get()) {
            runtime.asrSession.send(audioBytes);
            runtime.touch();
        }
    }

    public void control(String socketId, String payload) {
        RuntimeSession runtime = sessions.get(socketId);
        if (runtime == null || StringUtils.isBlank(payload)) {
            return;
        }
        String lower = payload.toLowerCase();
        if (lower.contains("play") && (lower.contains("complete") || lower.contains("done") || lower.contains("stop"))) {
            TenantHelper.dynamic(runtime.claims.tenantId(), () -> markListening(runtime));
        }
    }

    public void fail(String socketId, String reason) {
        RuntimeSession runtime = sessions.get(socketId);
        if (runtime != null) {
            TenantHelper.dynamic(runtime.claims.tenantId(), () -> markFailed(runtime, reason));
        }
    }

    public void disconnect(String socketId, String reason) {
        RuntimeSession runtime = sessions.remove(socketId);
        if (runtime == null || !runtime.closed.compareAndSet(false, true)) {
            return;
        }
        runtime.close();
        TenantHelper.dynamic(runtime.claims.tenantId(), () -> {
            AiRealtimeCallSession entity = runtime.entity;
            entity.setSessionState("FAILED".equals(entity.getSessionState()) ? "FAILED" : "ENDED");
            entity.setEndedAt(LocalDateTime.now());
            entity.setLastActivityAt(LocalDateTime.now());
            if ("FAILED".equals(entity.getSessionState()) && StringUtils.isBlank(entity.getFailureReason())) {
                entity.setFailureReason(reason);
            }
            sessionMapper.updateById(entity);
        });
        log.info("AI 实时语音会话结束，sessionId={}，businessCallId={}，customerLegUuid={}，reason={}",
            runtime.entity.getId(), runtime.entity.getBusinessCallId(), runtime.entity.getCustomerLegUuid(), reason);
    }

    private RuntimeSession createRuntime(WebSocketSession socket, AiRealtimeClaims claims,
                                         String businessCallId, String customerLegUuid) {
        AiSpeechProvider asrProvider = speechProviderSelector.requireDefaultStreamingAsr();
        AiSpeechProvider ttsProvider = speechProviderSelector.requireDefaultTts();
        AiRealtimeCallSession entity = new AiRealtimeCallSession();
        entity.setBusinessCallId(businessCallId);
        entity.setCustomerLegUuid(customerLegUuid);
        entity.setNodeId(claims.nodeId());
        entity.setFlowId(claims.flowId());
        entity.setAiAgentId(claims.agentId());
        entity.setAsrProviderId(asrProvider.getId());
        entity.setTtsProviderId(ttsProvider.getId());
        entity.setSessionState("CONNECTING");
        entity.setConnectedAt(LocalDateTime.now());
        entity.setLastActivityAt(LocalDateTime.now());
        entity.setVersion(0);
        sessionMapper.insert(entity);
        return new RuntimeSession(socket, claims, entity, asrProvider, ttsProvider);
    }

    private void start(RuntimeSession runtime) {
        try {
            runtime.asrSession = streamingAsrRegistry.get(runtime.asrProvider.getProviderType()).open(
                runtime.asrProvider,
                new StreamingAsrRequest("pcm", 16000, runtime.asrProvider.getAsrLanguage(),
                    Map.of("businessCallId", runtime.entity.getBusinessCallId(),
                        "customerLegUuid", runtime.entity.getCustomerLegUuid())),
                new StreamingAsrListener() {
                    @Override
                    public void onResult(AsrSegment segment) {
                        if (segment.finalResult() && StringUtils.isNotBlank(segment.text())) {
                            onFinalTranscript(runtime, segment.text().trim());
                        }
                    }

                    @Override
                    public void onCompleted(AsrTranscribeResult result) {
                        log.debug("AI 实时 ASR 已完成，sessionId={}，text={}", runtime.entity.getId(), result.fullText());
                    }

                    @Override
                    public void onError(String message) {
                        TenantHelper.dynamic(runtime.claims.tenantId(), () -> markFailed(runtime, message));
                    }
                });
            AiConversationStartResponse start = agentService.startRealtimeConversation(runtime.claims.agentId());
            runtime.entity.setConversationId(Long.valueOf(String.valueOf(start.getConversation().getId())));
            sessionMapper.updateById(runtime.entity);
            play(runtime, start.getMessage().getContent(), null);
            log.info("AI 实时语音会话建立，sessionId={}，businessCallId={}，customerLegUuid={}，agentId={}",
                runtime.entity.getId(), runtime.entity.getBusinessCallId(), runtime.entity.getCustomerLegUuid(), runtime.claims.agentId());
        } catch (Exception exception) {
            markFailed(runtime, exception.getMessage());
        }
    }

    private void onFinalTranscript(RuntimeSession runtime, String text) {
        if (!runtime.turnInProgress.compareAndSet(false, true) || !"LISTENING".equals(runtime.entity.getSessionState())) {
            log.debug("忽略非监听阶段的语音结果，sessionId={}，state={}，text={}",
                runtime.entity.getId(), runtime.entity.getSessionState(), text);
            return;
        }
        executor.execute(() -> TenantHelper.dynamic(runtime.claims.tenantId(), () -> processTurn(runtime, text)));
    }

    private void processTurn(RuntimeSession runtime, String text) {
        AiRealtimeCallTurn turn = new AiRealtimeCallTurn();
        turn.setRealtimeSessionId(runtime.entity.getId());
        turn.setSequenceNo(runtime.sequence.incrementAndGet());
        turn.setUserText(text);
        turn.setTurnState("THINKING");
        turn.setRecognizedAt(LocalDateTime.now());
        turnMapper.insert(turn);
        updateState(runtime, "THINKING", null);
        try {
            AiChatTurnResult result = agentService.chatOnce(runtime.claims.agentId(), runtime.entity.getConversationId(), text);
            runtime.entity.setConversationId(result.conversationId());
            turn.setAssistantText(result.answer());
            turn.setAnswerSource(result.sourceType());
            turn.setAnsweredAt(LocalDateTime.now());
            turn.setTurnState("SPEAKING");
            turnMapper.updateById(turn);
            sessionMapper.updateById(runtime.entity);
            play(runtime, result.answer(), turn);
        } catch (Exception exception) {
            turn.setTurnState("FAILED");
            turn.setFailureReason(limit(exception.getMessage()));
            turnMapper.updateById(turn);
            runtime.turnInProgress.set(false);
            updateState(runtime, "LISTENING", null);
            log.error("AI 实时语音轮次处理失败，sessionId={}，turn={}，error={}",
                runtime.entity.getId(), turn.getSequenceNo(), exception.getMessage(), exception);
        }
    }

    private void play(RuntimeSession runtime, String text, AiRealtimeCallTurn turn) {
        if (StringUtils.isBlank(text) || runtime.closed.get()) {
            runtime.turnInProgress.set(false);
            markListening(runtime);
            return;
        }
        TtsGenerateResult audio = ttsProviderRegistry.get(runtime.ttsProvider.getProviderType()).generate(
            runtime.ttsProvider,
            new TtsGenerateRequest(text, runtime.ttsProvider.getDefaultVoice(), "wav", 8000,
                "IVR_AI_REALTIME", Map.of("realtimeSessionId", runtime.entity.getId())));
        if (audio.audioBytes() == null || audio.audioBytes().length == 0) {
            throw new ServiceException("TTS 未返回可播放音频");
        }
        updateState(runtime, "SPEAKING", null);
        if (turn != null) {
            turn.setPlaybackStartedAt(LocalDateTime.now());
            turnMapper.updateById(turn);
        }
        Map<String, Object> response = Map.of("type", "streamAudio", "data", Map.of(
            "audioDataType", StringUtils.blankToDefault(audio.fileSuffix(), "wav").replace(".", ""),
            "sampleRate", 8000,
            "audioData", Base64.getEncoder().encodeToString(audio.audioBytes())
        ));
        send(runtime, JsonUtils.toJsonString(response));
        long duration = audio.durationMs() == null || audio.durationMs() <= 0
            ? estimateDuration(audio.audioBytes().length, 8000) : audio.durationMs();
        scheduler.schedule(() -> TenantHelper.dynamic(runtime.claims.tenantId(), () -> {
            if (turn != null && !"COMPLETED".equals(turn.getTurnState())) {
                turn.setTurnState("COMPLETED");
                turn.setPlaybackEndedAt(LocalDateTime.now());
                turnMapper.updateById(turn);
            }
            markListening(runtime);
        }), Instant.now().plusMillis(duration + 400));
    }

    private void send(RuntimeSession runtime, String payload) {
        try {
            synchronized (runtime.socket) {
                if (runtime.socket.isOpen()) {
                    runtime.socket.sendMessage(new TextMessage(payload));
                }
            }
        } catch (Exception exception) {
            throw new ServiceException("向 FreeSWITCH 返回 AI 音频失败：" + exception.getMessage());
        }
    }

    private void markListening(RuntimeSession runtime) {
        if (runtime.closed.get() || "FAILED".equals(runtime.entity.getSessionState())) {
            return;
        }
        runtime.turnInProgress.set(false);
        updateState(runtime, "LISTENING", null);
    }

    private void markFailed(RuntimeSession runtime, String reason) {
        updateState(runtime, "FAILED", limit(reason));
        log.error("AI 实时语音会话失败，sessionId={}，businessCallId={}，customerLegUuid={}，error={}",
            runtime.entity.getId(), runtime.entity.getBusinessCallId(), runtime.entity.getCustomerLegUuid(), reason);
    }

    private void updateState(RuntimeSession runtime, String state, String reason) {
        runtime.entity.setSessionState(state);
        runtime.entity.setLastActivityAt(LocalDateTime.now());
        runtime.entity.setFailureReason(reason);
        sessionMapper.updateById(runtime.entity);
    }

    private long estimateDuration(int bytes, int sampleRate) {
        return Math.max(600L, (long) Math.max(0, bytes - 44) * 1000L / (sampleRate * 2L));
    }

    private String limit(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    private static final class RuntimeSession {
        private final WebSocketSession socket;
        private final AiRealtimeClaims claims;
        private final AiRealtimeCallSession entity;
        private final AiSpeechProvider asrProvider;
        private final AiSpeechProvider ttsProvider;
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicBoolean turnInProgress = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile StreamingAsrSession asrSession;

        private RuntimeSession(WebSocketSession socket, AiRealtimeClaims claims, AiRealtimeCallSession entity,
                               AiSpeechProvider asrProvider, AiSpeechProvider ttsProvider) {
            this.socket = socket;
            this.claims = claims;
            this.entity = entity;
            this.asrProvider = asrProvider;
            this.ttsProvider = ttsProvider;
        }

        private void touch() {
            entity.setLastActivityAt(LocalDateTime.now());
        }

        private void close() {
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
