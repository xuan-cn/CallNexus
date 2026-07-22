package org.dromara.ai.service;

import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiCallRecordingSource;
import org.dromara.ai.domain.AiCallTranscript;
import org.dromara.ai.domain.AiCallTranscriptSegment;
import org.dromara.ai.domain.AiRealtimeCallSession;
import org.dromara.ai.domain.AiRealtimeCallTurn;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.domain.request.AiChatRequest;
import org.dromara.ai.domain.request.AiRealtimeTtsRequest;
import org.dromara.ai.domain.response.AiConversationStartResponse;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiCallRecordingSourceMapper;
import org.dromara.ai.mapper.AiCallTranscriptMapper;
import org.dromara.ai.mapper.AiCallTranscriptSegmentMapper;
import org.dromara.ai.mapper.AiRealtimeCallSessionMapper;
import org.dromara.ai.mapper.AiRealtimeCallTurnMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import org.dromara.common.redis.utils.RedisUtils;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

@Service
@Slf4j
public class AiRealtimeMrcpEventService {
    private static final String TRANSPORT = "UNIMRCP";
    private static final String SPEECH_TYPE_HEADER = "Speech-Type";
    private static final String EVENT_BODY_HEADER = "CallNexus-Event-Body";
    private static final String OWNERSHIP_KEY_PREFIX = "callnexus:ai:realtime:owner:";
    private static final Duration OWNERSHIP_TTL = Duration.ofMinutes(5);
    private static final String VAR_CUSTOMER_LEG_UUID = "callnexus_ai_customer_leg_uuid";
    private static final String VAR_BUSINESS_CALL_ID = "callnexus_business_call_id";
    private static final String VAR_AGENT_ID = "callnexus_ai_agent_id";
    private static final String VAR_FLOW_ID = "callnexus_ai_flow_id";
    private static final String VAR_NODE_ID = "callnexus_ai_node_id";
    private static final String VAR_OPENING_PREPLAYED = "callnexus_ai_opening_preplayed";
    private static final String SPEAKER_CUSTOMER = "CUSTOMER";
    private static final String SPEAKER_AI = "AI";
    private static final String SOURCE_REALTIME_ASR = "REALTIME_ASR";
    private static final String SOURCE_AI_GENERATED = "AI_GENERATED";
    private static final String TRANSCRIPT_STATUS_SUCCESS = "SUCCESS";
    private static final int MAX_MERGED_FOLLOWUP_LENGTH = 72;

    private final AiKnowledgeProperties properties;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final AiSpeechProviderSelector speechProviderSelector;
    private final AiAgentApplicationService agentService;
    private final AiAgentMapper agentMapper;
    private final AiRealtimeCallSessionMapper sessionMapper;
    private final AiRealtimeCallTurnMapper turnMapper;
    private final AiCallRecordingSourceMapper recordingSourceMapper;
    private final AiCallTranscriptMapper transcriptMapper;
    private final AiCallTranscriptSegmentMapper transcriptSegmentMapper;
    private final AiRealtimeTtsInternalService realtimeTtsService;
    private final ObjectProvider<AiRealtimeTelephonyGateway> telephonyGatewayProvider;
    @Qualifier("aiRealtimeExecutor")
    private final Executor executor;
    @Qualifier("aiRealtimeScheduler")
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<String, RuntimeSession> sessions = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();

    public AiRealtimeMrcpEventService(AiKnowledgeProperties properties,
                                      FreeSwitchNodeQueryService nodeQueryService,
                                      AiSpeechProviderSelector speechProviderSelector,
                                      AiAgentApplicationService agentService,
                                      AiAgentMapper agentMapper,
                                      AiRealtimeCallSessionMapper sessionMapper,
                                      AiRealtimeCallTurnMapper turnMapper,
                                      AiCallRecordingSourceMapper recordingSourceMapper,
                                      AiCallTranscriptMapper transcriptMapper,
                                      AiCallTranscriptSegmentMapper transcriptSegmentMapper,
                                      AiRealtimeTtsInternalService realtimeTtsService,
                                      ObjectProvider<AiRealtimeTelephonyGateway> telephonyGatewayProvider,
                                      @Qualifier("aiRealtimeExecutor") Executor executor,
                                      @Qualifier("aiRealtimeScheduler") ThreadPoolTaskScheduler scheduler) {
        this.properties = properties;
        this.nodeQueryService = nodeQueryService;
        this.speechProviderSelector = speechProviderSelector;
        this.agentService = agentService;
        this.agentMapper = agentMapper;
        this.sessionMapper = sessionMapper;
        this.turnMapper = turnMapper;
        this.recordingSourceMapper = recordingSourceMapper;
        this.transcriptMapper = transcriptMapper;
        this.transcriptSegmentMapper = transcriptSegmentMapper;
        this.realtimeTtsService = realtimeTtsService;
        this.telephonyGatewayProvider = telephonyGatewayProvider;
        this.executor = executor;
        this.scheduler = scheduler;
    }

    public void handle(Long nodeId, String eventName, String uuid, Map<String, String> headers) {
        long receivedAtNanos = System.nanoTime();
        if (!Boolean.TRUE.equals(properties.getRealtimeEnabled())) {
            return;
        }
        if (!isUniMrcpEvent(uuid, headers)) {
            if ("DETECTED_SPEECH".equals(eventName)) {
                log.debug("AI UniMRCP 过滤器丢弃 DETECTED_SPEECH，未识别为 UniMRCP 上下文，nodeId={}，uuid={}，speechType={}，headerKeys={}",
                    nodeId, uuid, header(headers, SPEECH_TYPE_HEADER), headers.keySet());
            }
            return;
        }
        if ("DETECTED_SPEECH".equals(eventName)) {
            log.info("AI UniMRCP 过滤器接受 DETECTED_SPEECH，nodeId={}，uuid={}，speechType={}",
                nodeId, uuid, header(headers, SPEECH_TYPE_HEADER));
        }
        String tenantId = nodeQueryService.findTenantId(nodeId);
        if (StringUtils.isBlank(tenantId)) {
            log.warn("AI UniMRCP 事件缺少租户上下文，nodeId={}，uuid={}，eventName={}", nodeId, uuid, eventName);
            return;
        }
        String ownershipUuid = firstNonBlank(header(headers, VAR_CUSTOMER_LEG_UUID), uuid);
        if (!acquireOwnership(ownershipUuid)) {
            return;
        }
        TenantHelper.dynamic(tenantId, () -> handleInTenant(tenantId, nodeId, eventName, uuid, headers, receivedAtNanos));
    }

    private void handleInTenant(String tenantId, Long nodeId, String eventName, String uuid, Map<String, String> headers,
                                long receivedAtNanos) {
        String customerLegUuid = firstNonBlank(header(headers, VAR_CUSTOMER_LEG_UUID), uuid);
        String businessCallId = firstNonBlank(header(headers, VAR_BUSINESS_CALL_ID), customerLegUuid);
        Long agentId = parseLong(header(headers, VAR_AGENT_ID));
        Long flowId = parseLong(header(headers, VAR_FLOW_ID));
        Long ivrNodeId = parseLong(header(headers, VAR_NODE_ID));
        Boolean openingPreplayed = parseBoolean(header(headers, VAR_OPENING_PREPLAYED));

        AiChannelVariables resolved = resolveMissingVariables(nodeId, customerLegUuid, businessCallId, agentId, flowId, ivrNodeId,
            openingPreplayed, receivedAtNanos);
        customerLegUuid = firstNonBlank(resolved.customerLegUuid(), customerLegUuid);
        businessCallId = firstNonBlank(resolved.businessCallId(), businessCallId);
        agentId = agentId == null ? resolved.agentId() : agentId;
        flowId = flowId == null ? resolved.flowId() : flowId;
        ivrNodeId = ivrNodeId == null ? resolved.ivrNodeId() : ivrNodeId;
        openingPreplayed = openingPreplayed == null ? resolved.openingPreplayed() : openingPreplayed;

        RuntimeSession existing = sessions.get(key(tenantId, customerLegUuid));
        if (isTerminal(eventName)) {
            if (existing != null) {
                end(existing, eventName);
            } else {
                log.info("忽略已结束或未建立的 AI UniMRCP 终止事件，tenantId={}，nodeId={}，eventName={}，uuid={}，customerLegUuid={}",
                    tenantId, nodeId, eventName, uuid, customerLegUuid);
            }
            return;
        }
        if (existing != null) {
            businessCallId = firstNonBlank(businessCallId, existing.businessCallId);
            agentId = agentId == null ? existing.agentId : agentId;
            flowId = flowId == null ? existing.entity.getFlowId() : flowId;
        }
        if (StringUtils.isBlank(customerLegUuid) || agentId == null || flowId == null) {
            log.debug("AI UniMRCP 事件变量尚未完整，等待后续事件，tenantId={}，nodeId={}，uuid={}，agentId={}，flowId={}，customerLegUuid={}，receivedCostMs={}",
                tenantId, nodeId, uuid, agentId, flowId, customerLegUuid, elapsedMillis(receivedAtNanos));
            return;
        }

        String application = firstNonBlank(header(headers, "current_application"), headers.get("Application"));
        String applicationData = firstNonBlank(header(headers, "current_application_data"), headers.get("Application-Data"));
        log.info("收到 AI UniMRCP 事件，tenantId={}，nodeId={}，eventName={}，uuid={}，customerLegUuid={}，agentId={}，application={}，applicationData={}，receivedCostMs={}",
            tenantId, nodeId, eventName, uuid, customerLegUuid, agentId, application, applicationData, elapsedMillis(receivedAtNanos));

        String key = key(tenantId, customerLegUuid);
        Long finalFlowId = flowId;
        Long finalIvrNodeId = ivrNodeId;
        Long finalAgentId = agentId;
        String finalBusinessCallId = businessCallId;
        String finalCustomerLegUuid = customerLegUuid;
        boolean finalOpeningPreplayed = Boolean.TRUE.equals(openingPreplayed);
        boolean speakComplete = isSpeakComplete(eventName, application);
        RuntimeSession runtime = sessions.computeIfAbsent(key, ignored -> createRuntime(tenantId, nodeId, finalFlowId, finalIvrNodeId,
            finalAgentId, finalBusinessCallId, finalCustomerLegUuid, finalOpeningPreplayed));
        runtime.touch();
        if (speakComplete && runtime.openingPreplayed) {
            runtime.preplayedOpeningCompleted.set(true);
        }
        if (runtime.started.compareAndSet(false, true)) {
            log.info("AI UniMRCP 首次完整事件已收到，准备异步启动会话，sessionId={}，businessCallId={}，customerLegUuid={}，eventName={}，application={}，applicationData={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, eventName, application, applicationData);
            executor.execute(() -> TenantHelper.dynamic(tenantId, () -> start(runtime)));
            return;
        }
        if (speakComplete) {
            onSpeakComplete(runtime, applicationData);
            return;
        }
        String speechType = header(headers, SPEECH_TYPE_HEADER);
        if ("DETECTED_SPEECH".equals(eventName) && "begin-speaking".equalsIgnoreCase(speechType)) {
            log.debug("AI UniMRCP 检测到用户开始说话，继续等待最终识别结果，sessionId={}，businessCallId={}",
                runtime.entity.getId(), runtime.businessCallId);
            return;
        }
        String recognized = recognizedText(headers);
        if (StringUtils.isBlank(recognized) && "DETECTED_SPEECH".equals(eventName)
            && "detected-speech".equalsIgnoreCase(speechType)) {
            recognized = parseNlsmlText(header(headers, EVENT_BODY_HEADER));
        }
        final String result = recognized;
        if ("DETECTED_SPEECH".equals(eventName) && StringUtils.isBlank(result)) {
            log.warn("收到 DETECTED_SPEECH 但未找到识别文本，sessionId={}，businessCallId={}，候选字段={}，speech相关事件头={}",
                runtime.entity.getId(), runtime.businessCallId, properties.getUnimrcp().getResultHeaderCandidates(),
                speechRelatedHeaders(headers));
            handleEmptyRecognition(runtime);
            return;
        }
        if (StringUtils.isNotBlank(result) && runtime.acceptRecognition(result)) {
            long recognitionAcceptedNanos = System.nanoTime();
            runtime.recognizing.set(false);
            log.info("AI UniMRCP 识别到用户语音，sessionId={}，businessCallId={}，text={}",
                runtime.entity.getId(), runtime.businessCallId, result.trim());
            LocalDateTime recognizedAt = LocalDateTime.now();
            executor.execute(() -> TenantHelper.dynamic(tenantId,
                () -> processTurn(runtime, result.trim(), recognitionAcceptedNanos)));
            appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_CUSTOMER, SOURCE_REALTIME_ASR,
                result.trim(), recognizedAt, null);
        }
    }

    private void handleEmptyRecognition(RuntimeSession runtime) {
        if (runtime.closed.get()) {
            return;
        }
        runtime.recognizing.set(false);
        boolean exists;
        try {
            exists = gateway().callExists(runtime.nodeId, runtime.customerLegUuid);
        } catch (Exception exception) {
            log.warn("AI UniMRCP 空识别后检查通道失败，稍后重试识别，sessionId={}，businessCallId={}，customerLegUuid={}，error={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, exception.getMessage());
            scheduleRecognizeRetry(runtime);
            return;
        }
        if (!exists) {
            log.info("AI UniMRCP 空识别后发现通道已不存在，结束会话，sessionId={}，businessCallId={}，customerLegUuid={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid);
            end(runtime, "CHANNEL_GONE_AFTER_EMPTY_RECOGNITION");
            return;
        }
        scheduleRecognizeRetry(runtime);
    }

    private void scheduleRecognizeRetry(RuntimeSession runtime) {
        if (runtime.closed.get()) {
            return;
        }
        scheduler.schedule(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (!runtime.closed.get()) {
                markListening(runtime);
                tryRecognize(runtime);
            }
        }), Instant.now().plusMillis(properties.getUnimrcp().getRecognizeRetryDelayMs()));
    }

    private AiChannelVariables resolveMissingVariables(Long nodeId, String customerLegUuid, String businessCallId, Long agentId,
                                                       Long flowId, Long ivrNodeId, Boolean openingPreplayed,
                                                       long receivedAtNanos) {
        if (StringUtils.isBlank(customerLegUuid) || (StringUtils.isNotBlank(businessCallId) && agentId != null
            && flowId != null && openingPreplayed != null)) {
            return AiChannelVariables.empty();
        }
        long startNanos = System.nanoTime();
        try {
            Map<String, String> variables = gateway().getChannelVariables(nodeId, customerLegUuid, VAR_CUSTOMER_LEG_UUID,
                VAR_BUSINESS_CALL_ID, VAR_AGENT_ID, VAR_FLOW_ID, VAR_NODE_ID, VAR_OPENING_PREPLAYED);
            if (variables.isEmpty()) {
                return AiChannelVariables.empty();
            }
            AiChannelVariables resolved = new AiChannelVariables(
                variables.get(VAR_CUSTOMER_LEG_UUID),
                variables.get(VAR_BUSINESS_CALL_ID),
                parseLong(variables.get(VAR_AGENT_ID)),
                parseLong(variables.get(VAR_FLOW_ID)),
                parseLong(variables.get(VAR_NODE_ID)),
                parseBoolean(variables.get(VAR_OPENING_PREPLAYED))
            );
            log.info("AI UniMRCP 已从 FreeSWITCH 补齐通道变量，customerLegUuid={}，resolvedCustomerLegUuid={}，agentId={}，flowId={}，costMs={}，receivedCostMs={}",
                customerLegUuid, resolved.customerLegUuid(), resolved.agentId(), resolved.flowId(), elapsedMillis(startNanos),
                elapsedMillis(receivedAtNanos));
            return resolved;
        } catch (Exception exception) {
            log.debug("AI UniMRCP 补齐通道变量失败，等待后续事件，customerLegUuid={}，error={}，costMs={}",
                customerLegUuid, exception.getMessage(), elapsedMillis(startNanos));
            return AiChannelVariables.empty();
        }
    }

    private RuntimeSession createRuntime(String tenantId, Long nodeId, Long flowId, Long ivrNodeId, Long agentId,
                                         String businessCallId, String customerLegUuid, boolean openingPreplayed) {
        long startNanos = System.nanoTime();
        var asrProvider = speechProviderSelector.requireDefaultStreamingAsr();
        var ttsProvider = defaultRealtimeTtsProvider();
        AiAgent agent = agentMapper.selectById(agentId);
        VoiceTransport transport = VoiceTransport.fromNullable(agent == null ? null : agent.getVoiceTransport());
        String wsUrl = agent == null ? null : agent.getVoiceTransportWsUrl();
        AiRealtimeCallSession entity = new AiRealtimeCallSession();
        entity.setBusinessCallId(businessCallId);
        entity.setCustomerLegUuid(customerLegUuid);
        entity.setNodeId(nodeId);
        entity.setFlowId(flowId);
        entity.setAiAgentId(agentId);
        entity.setAsrProviderId(asrProvider.getId());
        entity.setTtsProviderId(ttsProvider.getId());
        entity.setSessionState("CONNECTING");
        entity.setConnectedAt(LocalDateTime.now());
        entity.setLastActivityAt(LocalDateTime.now());
        entity.setVersion(0);
        sessionMapper.insert(entity);
        log.info("AI UniMRCP 运行会话已创建，sessionId={}，businessCallId={}，customerLegUuid={}，agentId={}，asrProviderId={}，ttsProviderId={}，voiceTransport={}，costMs={}",
            entity.getId(), businessCallId, customerLegUuid, agentId, asrProvider.getId(), ttsProvider.getId(),
            transport.name(), elapsedMillis(startNanos));
        return new RuntimeSession(tenantId, nodeId, agentId, businessCallId, customerLegUuid,
            ttsProvider.getDefaultVoice(), entity, openingPreplayed, transport, wsUrl);
    }

    private AiSpeechProvider defaultRealtimeTtsProvider() {
        try {
            return speechProviderSelector.requireDefaultStreamingTts();
        } catch (ServiceException exception) {
            return speechProviderSelector.requireDefaultTts();
        }
    }

    private void start(RuntimeSession runtime) {
        long startNanos = System.nanoTime();
        try {
            log.info("AI UniMRCP 开始建立会话，sessionId={}，businessCallId={}，customerLegUuid={}，agentId={}，voiceTransport={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, runtime.agentId, runtime.voiceTransport);
            try {
                gateway().applyVoiceTransport(runtime.nodeId, runtime.customerLegUuid, runtime.voiceTransport, runtime.voiceTransportWsUrl);
            } catch (Exception exception) {
                log.warn("AI UniMRCP 下发语音传输模式失败，将继续按 HTTP 模式尝试，sessionId={}，error={}",
                    runtime.entity.getId(), exception.getMessage());
            }
            long conversationNanos = System.nanoTime();
            AiConversationStartResponse start = agentService.startRealtimeConversation(runtime.agentId);
            long conversationCostMs = elapsedMillis(conversationNanos);
            long updateNanos = System.nanoTime();
            runtime.entity.setConversationId(Long.valueOf(String.valueOf(start.getConversation().getId())));
            sessionMapper.updateById(runtime.entity);
            long updateCostMs = elapsedMillis(updateNanos);
            runtime.conversationReady.set(true);
            appendRealtimeTranscriptSegment(runtime, SPEAKER_AI, SOURCE_AI_GENERATED, start.getMessage().getContent(), LocalDateTime.now(), runtime.agentId);
            if (runtime.openingPreplayed) {
                waitForPreplayedOpening(runtime, start.getMessage().getContent());
            } else {
                speak(runtime, start.getMessage().getContent(), null);
            }
            log.info("AI UniMRCP 会话已建立，sessionId={}，businessCallId={}，customerLegUuid={}，agentId={}，conversationCostMs={}，updateCostMs={}，totalCostMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, runtime.agentId,
                conversationCostMs, updateCostMs, elapsedMillis(startNanos));
        } catch (Exception exception) {
            fail(runtime, "AI UniMRCP 会话启动失败：" + exception.getMessage(), exception);
        }
    }

    private void processTurn(RuntimeSession runtime, String text, long recognitionAcceptedNanos) {
        long processStartedNanos = System.nanoTime();
        AiRealtimeCallTurn turn = new AiRealtimeCallTurn();
        turn.setRealtimeSessionId(runtime.entity.getId());
        turn.setSequenceNo(runtime.sequence.incrementAndGet());
        turn.setUserText(text);
        turn.setTurnState("THINKING");
        turn.setRecognizedAt(LocalDateTime.now());
        turnMapper.insert(turn);
        updateState(runtime, "THINKING", null);
        long prepareCostMs = elapsedMillis(processStartedNanos);

        runtime.segmenter = new SentenceSegmenter();
        synchronized (runtime.pendingSpeakSegments) {
            runtime.pendingSpeakSegments.clear();
        }
        runtime.speakSegmentSeq.set(0);
        runtime.currentTurn.set(turn);
        runtime.llmStreaming.set(true);

        AiChatRequest request = new AiChatRequest();
        request.setConversationId(runtime.entity.getConversationId());
        request.setMessage(text);

        StringBuilder fullAnswer = new StringBuilder();
        AtomicReference<String> sourceType = new AtomicReference<>();
        AtomicReference<Long> resolvedConversationId = new AtomicReference<>(runtime.entity.getConversationId());
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicLong firstDeltaNanos = new AtomicLong();
        AtomicLong firstSpeakReadyNanos = new AtomicLong();
        long chatNanos = System.nanoTime();

        BiConsumer<String, Object> consumer = (event, data) -> {
            if (!(data instanceof Map<?, ?> values)) {
                return;
            }
            switch (event) {
                case "conversation" -> {
                    if (values.get("conversationId") != null) {
                        resolvedConversationId.set(Long.valueOf(String.valueOf(values.get("conversationId"))));
                    }
                }
                case "delta" -> {
                    Object content = values.get("content");
                    if (content == null) {
                        return;
                    }
                    String piece = String.valueOf(content);
                    firstDeltaNanos.compareAndSet(0L, System.nanoTime());
                    fullAnswer.append(piece);
                    List<String> sentences = runtime.segmenter.append(piece);
                    if (!sentences.isEmpty() && firstSpeakReadyNanos.compareAndSet(0L, System.nanoTime())) {
                        log.info("AI UniMRCP 首个可播句已生成，sessionId={}，businessCallId={}，turn={}，recognizedToWorkerMs={}，prepareMs={}，firstDeltaMs={}，firstSpeakReadyMs={}，segmentLength={}",
                            runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(),
                            (processStartedNanos - recognitionAcceptedNanos) / 1_000_000L, prepareCostMs,
                            (firstDeltaNanos.get() - chatNanos) / 1_000_000L,
                            (firstSpeakReadyNanos.get() - chatNanos) / 1_000_000L,
                            sentences.get(0).length());
                    }
                    for (String sentence : sentences) {
                        enqueueSpeak(runtime, sentence);
                    }
                }
                case "completed" -> {
                    if (values.get("sourceType") != null) {
                        sourceType.set(String.valueOf(values.get("sourceType")));
                    }
                }
                case "error" -> failure.set(String.valueOf(values.get("message")));
                default -> {
                }
            }
        };

        try {
            agentService.streamChat(runtime.agentId, 0L, request, consumer);
            String tail = runtime.segmenter.drain();
            if (StringUtils.isNotBlank(tail)) {
                if (firstSpeakReadyNanos.compareAndSet(0L, System.nanoTime())) {
                    log.info("AI UniMRCP 首个可播句在模型结束时生成，sessionId={}，businessCallId={}，turn={}，recognizedToWorkerMs={}，prepareMs={}，firstDeltaMs={}，firstSpeakReadyMs={}，segmentLength={}",
                        runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(),
                        (processStartedNanos - recognitionAcceptedNanos) / 1_000_000L, prepareCostMs,
                        firstDeltaNanos.get() == 0L ? null : (firstDeltaNanos.get() - chatNanos) / 1_000_000L,
                        (firstSpeakReadyNanos.get() - chatNanos) / 1_000_000L, tail.length());
                }
                enqueueSpeak(runtime, tail);
            }
            long chatCostMs = elapsedMillis(chatNanos);
            if (failure.get() != null) {
                throw new ServiceException(failure.get());
            }
            runtime.entity.setConversationId(resolvedConversationId.get());
            LocalDateTime answeredAt = LocalDateTime.now();
            turn.setAssistantText(fullAnswer.toString());
            turn.setAnswerSource(sourceType.get());
            turn.setAnsweredAt(answeredAt);
            if ("THINKING".equals(turn.getTurnState())) {
                turn.setTurnState("SPEAKING");
            }
            turnMapper.updateById(turn);
            sessionMapper.updateById(runtime.entity);
            runtime.llmStreaming.set(false);
            prewarmPendingSegments(runtime);
            appendRealtimeTranscriptSegment(runtime, SPEAKER_AI, SOURCE_AI_GENERATED, fullAnswer.toString(), answeredAt, runtime.agentId);
            log.info("AI UniMRCP 轮次回答完成，sessionId={}，businessCallId={}，turn={}，source={}，answerLength={}，chatCostMs={}",
                runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(), sourceType.get(),
                fullAnswer.length(), chatCostMs);
            boolean empty;
            synchronized (runtime.pendingSpeakSegments) {
                empty = runtime.pendingSpeakSegments.isEmpty();
            }
            if (empty && !runtime.waitingSpeakComplete.get()) {
                AiRealtimeCallTurn pendingTurn = runtime.currentTurn.getAndSet(null);
                if (pendingTurn != null) {
                    finishTurn(runtime, pendingTurn);
                }
            }
        } catch (Exception exception) {
            runtime.llmStreaming.set(false);
            synchronized (runtime.pendingSpeakSegments) {
                runtime.pendingSpeakSegments.clear();
            }
            ScheduledFuture<?> pending = runtime.pendingSpeakTimer.getAndSet(null);
            if (pending != null) {
                pending.cancel(false);
            }
            runtime.waitingSpeakComplete.set(false);
            runtime.activeSpeak.set(null);
            turn.setTurnState("FAILED");
            turn.setFailureReason(limit(exception.getMessage()));
            turnMapper.updateById(turn);
            runtime.turnInProgress.set(false);
            runtime.currentTurn.compareAndSet(turn, null);
            updateState(runtime, "LISTENING", null);
            log.error("AI UniMRCP 轮次处理失败，sessionId={}，turn={}，text={}，error={}，chatCostMs={}",
                runtime.entity.getId(), turn.getSequenceNo(), text, exception.getMessage(), elapsedMillis(chatNanos), exception);
        }
    }

    private void waitForPreplayedOpening(RuntimeSession runtime, String openingText) {
        updateState(runtime, "SPEAKING", null);
        if (runtime.preplayedOpeningCompleted.get() || !runtime.waitingSpeakComplete.get()) {
            markListening(runtime);
            tryRecognize(runtime);
            return;
        }
        long delay = estimateSpeakDelay(openingText) + properties.getUnimrcp().getSpeakCompleteDelayMs();
        ScheduledFuture<?> old = runtime.pendingSpeakTimer.getAndSet(null);
        if (old != null) {
            old.cancel(false);
        }
        ScheduledFuture<?> handle = scheduler.schedule(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (!runtime.waitingSpeakComplete.compareAndSet(true, false)) {
                return;
            }
            log.warn("AI UniMRCP 未收到首句播报完成事件，按估算时长启动识别，sessionId={}，businessCallId={}，customerLegUuid={}，delayMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, delay);
            markListening(runtime);
            tryRecognize(runtime);
        }), Instant.now().plusMillis(delay));
        runtime.pendingSpeakTimer.set(handle);
        log.info("AI UniMRCP 首句已由 dialplan 播放，等待播报完成后启动识别，sessionId={}，businessCallId={}，customerLegUuid={}，delayMs={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, delay);
    }

    private void speak(RuntimeSession runtime, String text, AiRealtimeCallTurn turn) {
        if (StringUtils.isBlank(text)) {
            markListening(runtime);
            return;
        }
        long speakNanos = System.nanoTime();
        updateState(runtime, "SPEAKING", null);
        long stateCostMs = elapsedMillis(speakNanos);
        runtime.waitingSpeakComplete.set(true);
        runtime.recognizing.set(false);
        if (turn != null && !"SPEAKING".equals(turn.getTurnState()) && !"COMPLETED".equals(turn.getTurnState())) {
            turn.setTurnState("SPEAKING");
            turn.setPlaybackStartedAt(LocalDateTime.now());
            turnMapper.updateById(turn);
        }
        long gatewayNanos = System.nanoTime();
        int seq = runtime.speakSegmentSeq.incrementAndGet();
        String turnId = resolveTurnId(runtime, turn);
        boolean turnEnd = isTurnEnd(runtime, turn);
        ActiveSpeak activeSpeak = new ActiveSpeak(turnId, seq, text);
        runtime.activeSpeak.set(activeSpeak);
        log.info("AI UniMRCP 准备提交播报，sessionId={}，businessCallId={}，customerLegUuid={}，textLength={}，voice={}，turnId={}，seq={}，turnEnd={}，stateCostMs={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, text.length(), runtime.ttsVoice,
            turnId, seq, turnEnd, stateCostMs);
        gateway().speak(runtime.nodeId, runtime.customerLegUuid, text, runtime.ttsVoice, turnId, seq, turnEnd);
        long gatewayCostMs = elapsedMillis(gatewayNanos);
        long delay = estimateSpeakDelay(text) + properties.getUnimrcp().getSpeakCompleteDelayMs();
        ScheduledFuture<?> old = runtime.pendingSpeakTimer.getAndSet(null);
        if (old != null) {
            old.cancel(false);
        }
        ScheduledFuture<?> handle = scheduler.schedule(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (runtime.activeSpeak.get() != activeSpeak) {
                return;
            }
            log.warn("AI UniMRCP 未收到播报完成事件，按估算时长兜底处理，sessionId={}，businessCallId={}，customerLegUuid={}，delayMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, delay);
            completeSpeak(runtime, activeSpeak, "TIMEOUT");
        }), Instant.now().plusMillis(delay));
        runtime.pendingSpeakTimer.set(handle);
        log.info("AI UniMRCP 已提交播报，sessionId={}，businessCallId={}，customerLegUuid={}，textLength={}，delayMs={}，gatewayCostMs={}，totalCostMs={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, text.length(), delay,
            gatewayCostMs, elapsedMillis(speakNanos));
    }

    /**
     * 解析下发给插件的轮次标识：正式轮次用 turn 序号，开场白用固定 "opening"。
     * 同一轮多段返回一致的 turnId，插件据此复用同一条 TTS WebSocket。
     */
    private String resolveTurnId(RuntimeSession runtime, AiRealtimeCallTurn turn) {
        if (turn != null && turn.getSequenceNo() != null) {
            return "turn-" + turn.getSequenceNo();
        }
        return "opening";
    }

    /**
     * 判定当前段是否为本轮最后一段：
     * <ul>
     *   <li>开场白（turn==null）为单段，直接视为本轮结束；</li>
     *   <li>正式轮次在 LLM 已结束（llmStreaming==false）且待播队列已空时，本段即最后一段。</li>
     * </ul>
     */
    private boolean isTurnEnd(RuntimeSession runtime, AiRealtimeCallTurn turn) {
        if (turn == null) {
            return true;
        }
        if (runtime.llmStreaming.get()) {
            return false;
        }
        synchronized (runtime.pendingSpeakSegments) {
            return runtime.pendingSpeakSegments.isEmpty();
        }
    }

    private void onSpeakComplete(RuntimeSession runtime, String applicationData) {
        if (runtime.openingPreplayed && !runtime.conversationReady.get()) {
            runtime.preplayedOpeningCompleted.set(true);
            runtime.waitingSpeakComplete.set(false);
            ScheduledFuture<?> pending = runtime.pendingSpeakTimer.getAndSet(null);
            if (pending != null) {
                pending.cancel(false);
            }
            log.info("AI UniMRCP 首句播报已完成，会话仍在初始化，等待会话创建后启动识别，sessionId={}，businessCallId={}，customerLegUuid={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid);
            return;
        }
        ActiveSpeak activeSpeak = runtime.activeSpeak.get();
        if (activeSpeak == null) {
            log.debug("AI UniMRCP 忽略无活动播放段的完成事件，sessionId={}，businessCallId={}，applicationData={}",
                runtime.entity.getId(), runtime.businessCallId, applicationData);
            return;
        }
        if (!matchesSpeakCompletion(activeSpeak.text(), applicationData)) {
            log.warn("AI UniMRCP 忽略迟到或不匹配的播报完成事件，sessionId={}，businessCallId={}，activeTurnId={}，activeSeq={}，applicationData={}",
                runtime.entity.getId(), runtime.businessCallId, activeSpeak.turnId(), activeSpeak.seq(), applicationData);
            return;
        }
        completeSpeak(runtime, activeSpeak, "EVENT");
    }

    private void completeSpeak(RuntimeSession runtime, ActiveSpeak expected, String source) {
        if (!runtime.activeSpeak.compareAndSet(expected, null)) {
            return;
        }
        runtime.waitingSpeakComplete.set(false);
        ScheduledFuture<?> pendingTimer = runtime.pendingSpeakTimer.getAndSet(null);
        if (pendingTimer != null) {
            pendingTimer.cancel(false);
        }
        log.info("AI UniMRCP 播报段完成，sessionId={}，businessCallId={}，customerLegUuid={}，turnId={}，seq={}，source={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid,
            expected.turnId(), expected.seq(), source);
        boolean hasNext;
        synchronized (runtime.pendingSpeakSegments) {
            hasNext = !runtime.pendingSpeakSegments.isEmpty();
        }
        if (hasNext) {
            dispatchNextSegment(runtime);
            return;
        }
        if (runtime.llmStreaming.get()) {
            return;
        }
        AiRealtimeCallTurn turn = runtime.currentTurn.getAndSet(null);
        if (turn != null) {
            finishTurn(runtime, turn);
        } else {
            markListening(runtime);
            tryRecognize(runtime);
        }
    }

    private void enqueueSpeak(RuntimeSession runtime, String sentence) {
        if (StringUtils.isBlank(sentence)) {
            return;
        }
        int pending;
        synchronized (runtime.pendingSpeakSegments) {
            enqueuePendingSegment(runtime.pendingSpeakSegments, sentence, runtime.waitingSpeakComplete.get());
            pending = runtime.pendingSpeakSegments.size();
        }
        log.info("AI UniMRCP 分句入队，sessionId={}，businessCallId={}，turn={}，segLen={}，pending={}，speaking={}",
            runtime.entity.getId(), runtime.businessCallId,
            runtime.currentTurn.get() == null ? null : runtime.currentTurn.get().getSequenceNo(),
            sentence.length(), pending, runtime.waitingSpeakComplete.get());
        dispatchNextSegment(runtime);
    }

    private void prewarmPendingSegments(RuntimeSession runtime) {
        List<String> pending;
        synchronized (runtime.pendingSpeakSegments) {
            pending = List.copyOf(runtime.pendingSpeakSegments);
        }
        for (String text : pending) {
            executor.execute(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
                if (runtime.closed.get()) {
                    return;
                }
                long startNanos = System.nanoTime();
                try {
                    AiRealtimeTtsRequest request = new AiRealtimeTtsRequest();
                    request.setTenantId(runtime.tenantId);
                    request.setText(text);
                    request.setVoice(runtime.ttsVoice);
                    request.setFormat("pcm");
                    request.setSampleRate(8000);
                    realtimeTtsService.generateForStream(request);
                    log.info("AI UniMRCP 后续播报预热完成，sessionId={}，businessCallId={}，textLength={}，costMs={}",
                        runtime.entity.getId(), runtime.businessCallId, text.length(), elapsedMillis(startNanos));
                } catch (Exception exception) {
                    log.warn("AI UniMRCP 后续播报预热失败，将在播放时正常合成，sessionId={}，businessCallId={}，textLength={}，error={}",
                        runtime.entity.getId(), runtime.businessCallId, text.length(), exception.getMessage());
                }
            }));
        }
    }

    static void enqueuePendingSegment(Deque<String> pendingSegments, String sentence, boolean speaking) {
        String tail = pendingSegments.peekLast();
        if (speaking && tail != null && tail.length() + sentence.length() <= MAX_MERGED_FOLLOWUP_LENGTH) {
            pendingSegments.pollLast();
            pendingSegments.offerLast(tail + sentence);
            return;
        }
        pendingSegments.offerLast(sentence);
    }

    static boolean matchesSpeakCompletion(String activeText, String applicationData) {
        if (StringUtils.isBlank(applicationData)) {
            return false;
        }
        return applicationData.endsWith("|" + activeText) || applicationData.contains(activeText);
    }

    private void dispatchNextSegment(RuntimeSession runtime) {
        String next;
        synchronized (runtime.pendingSpeakSegments) {
            if (runtime.waitingSpeakComplete.get()) {
                return;
            }
            next = runtime.pendingSpeakSegments.pollFirst();
            if (next == null) {
                return;
            }
            runtime.waitingSpeakComplete.set(true);
        }
        AiRealtimeCallTurn turn = runtime.currentTurn.get();
        log.info("AI UniMRCP 分句派发，sessionId={}，businessCallId={}，turn={}，text={}",
            runtime.entity.getId(), runtime.businessCallId,
            turn == null ? null : turn.getSequenceNo(), next);
        speak(runtime, next, turn);
    }

    private void finishTurn(RuntimeSession runtime, AiRealtimeCallTurn turn) {
        String state = turn.getTurnState();
        if (!"COMPLETED".equals(state) && !"FAILED".equals(state)) {
            turn.setTurnState("COMPLETED");
            turn.setPlaybackEndedAt(LocalDateTime.now());
            turnMapper.updateById(turn);
        }
        log.info("AI UniMRCP 回合结束，sessionId={}，businessCallId={}，turn={}，totalMs={}",
            runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(),
            Duration.between(turn.getRecognizedAt(), LocalDateTime.now()).toMillis());
        runtime.turnInProgress.set(false);
        markListening(runtime);
        tryRecognize(runtime);
    }

    private void tryRecognize(RuntimeSession runtime) {
        if (runtime.closed.get() || !"LISTENING".equals(runtime.entity.getSessionState())) {
            return;
        }
        if (!runtime.recognizing.compareAndSet(false, true)) {
            return;
        }
        long recognizeNanos = System.nanoTime();
        try {
            gateway().recognize(runtime.nodeId, runtime.customerLegUuid);
            log.info("AI UniMRCP 已提交识别，sessionId={}，businessCallId={}，customerLegUuid={}，gatewayCostMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, elapsedMillis(recognizeNanos));
        } catch (Exception exception) {
            runtime.recognizing.set(false);
            log.warn("AI UniMRCP 提交识别失败，稍后重试，sessionId={}，businessCallId={}，customerLegUuid={}，error={}，gatewayCostMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, exception.getMessage(), elapsedMillis(recognizeNanos));
            scheduler.schedule(() -> TenantHelper.dynamic(runtime.tenantId, () -> tryRecognize(runtime)),
                Instant.now().plusMillis(properties.getUnimrcp().getRecognizeRetryDelayMs()));
        }
    }

    private void markListening(RuntimeSession runtime) {
        if (runtime.closed.get() || "FAILED".equals(runtime.entity.getSessionState())) {
            return;
        }
        runtime.turnInProgress.set(false);
        updateState(runtime, "LISTENING", null);
    }

    private void end(RuntimeSession runtime, String reason) {
        sessions.remove(key(runtime.tenantId, runtime.customerLegUuid));
        releaseOwnership(runtime.customerLegUuid);
        if (!runtime.closed.compareAndSet(false, true)) {
            return;
        }
        runtime.activeSpeak.set(null);
        runtime.waitingSpeakComplete.set(false);
        ScheduledFuture<?> pendingSpeak = runtime.pendingSpeakTimer.getAndSet(null);
        if (pendingSpeak != null) {
            pendingSpeak.cancel(false);
        }
        runtime.entity.setSessionState("FAILED".equals(runtime.entity.getSessionState()) ? "FAILED" : "ENDED");
        runtime.entity.setEndedAt(LocalDateTime.now());
        runtime.entity.setLastActivityAt(LocalDateTime.now());
        sessionMapper.updateById(runtime.entity);
        finishRealtimeTranscript(runtime);
        log.info("AI UniMRCP 会话结束，sessionId={}，businessCallId={}，customerLegUuid={}，reason={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, reason);
    }

    private void appendRealtimeTranscriptSegment(RuntimeSession runtime, String speaker, String sourceType, String text,
                                                 LocalDateTime messageTime, Long agentId) {
        if (runtime == null || StringUtils.isBlank(runtime.businessCallId) || StringUtils.isBlank(text)) {
            return;
        }
        try {
            synchronized (runtime.transcriptPersistenceLock) {
                AiCallRecordingSource source = recordingSourceMapper.selectOne(new LambdaQueryWrapper<AiCallRecordingSource>()
                    .eq(AiCallRecordingSource::getBusinessCallId, runtime.businessCallId)
                    .last("limit 1"));
                if (source == null) {
                    log.debug("跳过实时转写入库，未找到通话会话，businessCallId={}，speaker={}，sourceType={}",
                        runtime.businessCallId, speaker, sourceType);
                    return;
                }
                AiCallTranscript transcript = ensureRealtimeTranscript(runtime, source);
                AiCallTranscriptSegment segment = new AiCallTranscriptSegment();
                segment.setTranscriptId(transcript.getId());
                segment.setCallSessionId(source.getId());
                segment.setBusinessCallId(runtime.businessCallId);
                segment.setSpeaker(speaker);
                segment.setSourceType(sourceType);
                segment.setLegUuid(SPEAKER_CUSTOMER.equals(speaker) ? runtime.customerLegUuid : null);
                segment.setAgentId(agentId);
                segment.setSentenceIndex(runtime.transcriptSentenceIndex.incrementAndGet());
                segment.setMessageTime(messageTime == null ? LocalDateTime.now() : messageTime);
                segment.setTextContent(text.trim());
                segment.setFinalResult(true);
                transcriptSegmentMapper.insert(segment);

                transcript.setFullText(appendTranscriptLine(transcript.getFullText(), speaker, text.trim()));
                transcriptMapper.updateById(transcript);
                log.info("AI 实时通话转写已入库，sessionId={}，businessCallId={}，speaker={}，sourceType={}，sentenceIndex={}",
                    runtime.entity.getId(), runtime.businessCallId, speaker, sourceType, segment.getSentenceIndex());
            }
        } catch (Exception exception) {
            log.warn("AI 实时通话转写入库失败，不影响通话，sessionId={}，businessCallId={}，speaker={}，error={}",
                runtime.entity.getId(), runtime.businessCallId, speaker, exception.getMessage());
        }
    }

    private void appendRealtimeTranscriptSegmentAsync(RuntimeSession runtime, String speaker, String sourceType, String text,
                                                       LocalDateTime messageTime, Long agentId) {
        executor.execute(() -> TenantHelper.dynamic(runtime.tenantId,
            () -> appendRealtimeTranscriptSegment(runtime, speaker, sourceType, text, messageTime, agentId)));
    }

    private AiCallTranscript ensureRealtimeTranscript(RuntimeSession runtime, AiCallRecordingSource source) {
        AiCallTranscript transcript = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
            .eq(AiCallTranscript::getCallSessionId, source.getId())
            .last("limit 1"));
        if (transcript == null) {
            transcript = new AiCallTranscript();
            transcript.setCallSessionId(source.getId());
            transcript.setBusinessCallId(runtime.businessCallId);
            transcript.setStartedAt(firstNonNull(source.getAnsweredAt(), source.getStartedAt(), runtime.entity.getConnectedAt()));
        }
        transcript.setProviderId(runtime.entity.getAsrProviderId());
        transcript.setProviderType(TRANSPORT);
        transcript.setInputMediaId(source.getRecordingMediaId());
        transcript.setRecordingOssId(source.getRecordingOssId());
        transcript.setStatus(TRANSCRIPT_STATUS_SUCCESS);
        transcript.setFailureReason(null);
        if (transcript.getStartedAt() == null) {
            transcript.setStartedAt(firstNonNull(source.getAnsweredAt(), source.getStartedAt(), runtime.entity.getConnectedAt()));
        }
        if (transcript.getId() == null) {
            transcriptMapper.insert(transcript);
        } else {
            transcriptMapper.updateById(transcript);
        }
        return transcript;
    }

    private void finishRealtimeTranscript(RuntimeSession runtime) {
        if (runtime == null || StringUtils.isBlank(runtime.businessCallId)) {
            return;
        }
        try {
            synchronized (runtime.transcriptPersistenceLock) {
                AiCallRecordingSource source = recordingSourceMapper.selectOne(new LambdaQueryWrapper<AiCallRecordingSource>()
                    .eq(AiCallRecordingSource::getBusinessCallId, runtime.businessCallId)
                    .last("limit 1"));
                if (source == null) {
                    return;
                }
                AiCallTranscript transcript = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
                    .eq(AiCallTranscript::getCallSessionId, source.getId())
                    .last("limit 1"));
                if (transcript == null) {
                    return;
                }
                transcript.setFinishedAt(LocalDateTime.now());
                transcriptMapper.updateById(transcript);
            }
        } catch (Exception exception) {
            log.debug("AI 实时通话转写结束时间更新失败，businessCallId={}，error={}", runtime.businessCallId, exception.getMessage());
        }
    }

    private String appendTranscriptLine(String current, String speaker, String text) {
        String label = switch (StringUtils.blankToDefault(speaker, "")) {
            case SPEAKER_CUSTOMER -> "客户";
            case SPEAKER_AI -> "AI";
            default -> "未知";
        };
        String line = label + "：" + text;
        return StringUtils.isBlank(current) ? line : current + "\n" + line;
    }

    private LocalDateTime firstNonNull(LocalDateTime first, LocalDateTime second, LocalDateTime third) {
        if (first != null) {
            return first;
        }
        return second != null ? second : third;
    }

    private void fail(RuntimeSession runtime, String reason, Exception exception) {
        updateState(runtime, "FAILED", limit(reason));
        log.error("AI UniMRCP 会话失败，sessionId={}，businessCallId={}，customerLegUuid={}，error={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, reason, exception);
    }

    private void updateState(RuntimeSession runtime, String state, String reason) {
        runtime.entity.setSessionState(state);
        runtime.entity.setLastActivityAt(LocalDateTime.now());
        runtime.entity.setFailureReason(reason);
        sessionMapper.updateById(runtime.entity);
    }

    private AiRealtimeTelephonyGateway gateway() {
        return telephonyGatewayProvider.getIfAvailable(() -> {
            throw new IllegalStateException("AI UniMRCP FreeSWITCH 网关未加载");
        });
    }

    private String parseNlsmlText(String nlsml) {
        if (StringUtils.isBlank(nlsml)) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(nlsml)));
            XPath xpath = XPathFactory.newInstance().newXPath();
            String text = (String) xpath.evaluate("/result/interpretation/input/text()", document, XPathConstants.STRING);
            if (StringUtils.isBlank(text)) {
                text = (String) xpath.evaluate("/result/interpretation/instance/text()", document, XPathConstants.STRING);
            }
            return StringUtils.isBlank(text) ? null : text.trim();
        } catch (Exception exception) {
            log.warn("解析 DETECTED_SPEECH NLSML 失败：{}", exception.getMessage());
            return null;
        }
    }

    private boolean acquireOwnership(String customerLegUuid) {
        if (StringUtils.isBlank(customerLegUuid)) {
            return true;
        }
        String key = OWNERSHIP_KEY_PREFIX + customerLegUuid;
        String current = RedisUtils.getCacheObject(key);
        if (instanceId.equals(current)) {
            RedisUtils.expire(key, OWNERSHIP_TTL);
            return true;
        }
        return RedisUtils.setObjectIfAbsent(key, instanceId, OWNERSHIP_TTL);
    }

    private void releaseOwnership(String customerLegUuid) {
        if (StringUtils.isBlank(customerLegUuid)) {
            return;
        }
        String key = OWNERSHIP_KEY_PREFIX + customerLegUuid;
        if (instanceId.equals(RedisUtils.getCacheObject(key))) {
            RedisUtils.deleteObject(key);
        }
    }

    private boolean isUniMrcpEvent(String uuid, Map<String, String> headers) {
        if (TRANSPORT.equalsIgnoreCase(header(headers, "callnexus_ai_transport"))) {
            return true;
        }
        if (StringUtils.isNotBlank(recognizedText(headers))) {
            return true;
        }
        if (StringUtils.isNotBlank(header(headers, VAR_CUSTOMER_LEG_UUID))
            && parseLong(header(headers, VAR_AGENT_ID)) != null) {
            return true;
        }
        // DETECTED_SPEECH 等异步语音事件由 switch_ivr_async.c 直接 fire，不携带 variable_callnexus_ai_* 头。
        // 若该通道之前已经建立过 UniMRCP 会话（sessions 中存在以 :uuid 结尾的 key），则视为 UniMRCP 上下文继续处理。
        if (StringUtils.isNotBlank(uuid)) {
            String suffix = ":" + uuid;
            for (String key : sessions.keySet()) {
                if (key.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTerminal(String eventName) {
        return "CHANNEL_HANGUP".equals(eventName)
            || "CHANNEL_HANGUP_COMPLETE".equals(eventName)
            || "CHANNEL_DESTROY".equals(eventName);
    }

    private boolean isSpeakComplete(String eventName, String application) {
        return "CHANNEL_EXECUTE_COMPLETE".equals(eventName) && "speak".equalsIgnoreCase(application);
    }

    private String recognizedText(Map<String, String> headers) {
        String candidates = properties.getUnimrcp().getResultHeaderCandidates();
        if (StringUtils.isBlank(candidates)) {
            return null;
        }
        for (String name : candidates.split(",")) {
            String value = header(headers, name.trim());
            if (StringUtils.isNotBlank(value)) {
                return stripResult(value);
            }
        }
        return null;
    }

    private String stripResult(String value) {
        String text = value == null ? null : value.trim();
        if (StringUtils.isBlank(text)) {
            return null;
        }
        int cdataStart = text.indexOf("<![CDATA[");
        if (cdataStart >= 0) {
            int start = cdataStart + "<![CDATA[".length();
            int end = text.indexOf("]]>", start);
            if (end > start) {
                return text.substring(start, end).trim();
            }
        }
        return text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private Map<String, String> speechRelatedHeaders(Map<String, String> headers) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        headers.forEach((key, value) -> {
            if (key != null && key.toLowerCase(Locale.ROOT).contains("speech")) {
                result.put(key, value);
            }
        });
        return result;
    }

    private Long parseLong(String value) {
        try {
            return StringUtils.isBlank(value) ? null : Long.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean parseBoolean(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim());
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private String header(Map<String, String> headers, String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        String trimmed = name.trim();
        String value = headers.get(trimmed);
        if (StringUtils.isNotBlank(value)) {
            return value;
        }
        if (trimmed.startsWith("variable_")) {
            value = headers.get(trimmed.substring("variable_".length()));
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        } else {
            value = headers.get("variable_" + trimmed);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private long estimateSpeakDelay(String text) {
        return Math.max(1200L, text.length() * 180L);
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String limit(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    private String key(String tenantId, String customerLegUuid) {
        return tenantId + ":" + customerLegUuid;
    }

    private record AiChannelVariables(String customerLegUuid, String businessCallId, Long agentId, Long flowId, Long ivrNodeId,
                                      Boolean openingPreplayed) {
        private static AiChannelVariables empty() {
            return new AiChannelVariables(null, null, null, null, null, null);
        }
    }

    private record ActiveSpeak(String turnId, int seq, String text) {
    }

    private static final class RuntimeSession {
        private final String tenantId;
        private final Long nodeId;
        private final Long agentId;
        private final String businessCallId;
        private final String customerLegUuid;
        private final String ttsVoice;
        private final AiRealtimeCallSession entity;
        private final boolean openingPreplayed;
        private final VoiceTransport voiceTransport;
        private final String voiceTransportWsUrl;
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean conversationReady = new AtomicBoolean();
        private final AtomicBoolean preplayedOpeningCompleted = new AtomicBoolean();
        private final AtomicBoolean turnInProgress = new AtomicBoolean();
        private final AtomicBoolean waitingSpeakComplete = new AtomicBoolean();
        private final AtomicBoolean recognizing = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger transcriptSentenceIndex = new AtomicInteger();
        private final Object transcriptPersistenceLock = new Object();
        final Deque<String> pendingSpeakSegments = new ArrayDeque<>();
        final AtomicReference<AiRealtimeCallTurn> currentTurn = new AtomicReference<>();
        final AtomicReference<ActiveSpeak> activeSpeak = new AtomicReference<>();
        final AtomicBoolean llmStreaming = new AtomicBoolean();
        final AtomicReference<ScheduledFuture<?>> pendingSpeakTimer = new AtomicReference<>();
        /** 当前轮内的 TTS 段序号，随 speak 递增；每轮开始由 processTurn 重置。 */
        final AtomicInteger speakSegmentSeq = new AtomicInteger();
        volatile SentenceSegmenter segmenter;
        private volatile String lastRecognition;
        private volatile LocalDateTime lastActivityAt;

        private RuntimeSession(String tenantId, Long nodeId, Long agentId, String businessCallId,
                               String customerLegUuid, String ttsVoice, AiRealtimeCallSession entity, boolean openingPreplayed,
                               VoiceTransport voiceTransport, String voiceTransportWsUrl) {
            this.tenantId = tenantId;
            this.nodeId = nodeId;
            this.agentId = agentId;
            this.businessCallId = businessCallId;
            this.customerLegUuid = customerLegUuid;
            this.ttsVoice = ttsVoice;
            this.entity = entity;
            this.openingPreplayed = openingPreplayed;
            this.voiceTransport = voiceTransport == null ? VoiceTransport.HTTP : voiceTransport;
            this.voiceTransportWsUrl = voiceTransportWsUrl;
            this.waitingSpeakComplete.set(openingPreplayed);
            this.lastActivityAt = LocalDateTime.now();
        }

        private void touch() {
            this.lastActivityAt = LocalDateTime.now();
        }

        private boolean acceptRecognition(String text) {
            String normalized = text.toLowerCase(Locale.ROOT).trim();
            if (normalized.equals(lastRecognition)) {
                return false;
            }
            if (!turnInProgress.compareAndSet(false, true)) {
                return false;
            }
            lastRecognition = normalized;
            return true;
        }
    }
}
