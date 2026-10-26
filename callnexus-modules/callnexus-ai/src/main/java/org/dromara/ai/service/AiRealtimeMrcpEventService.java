package org.dromara.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.request.AiRealtimeTtsRequest;
import org.dromara.ai.domain.response.AiConversationStartResponse;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.domain.response.AiWorkflowCustomerContext;
import org.dromara.ai.domain.response.AiWorkflowVoiceExecutionResponse;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiCallRecordingSourceMapper;
import org.dromara.ai.mapper.AiCallTranscriptMapper;
import org.dromara.ai.mapper.AiCallTranscriptSegmentMapper;
import org.dromara.ai.mapper.AiRealtimeCallSessionMapper;
import org.dromara.ai.mapper.AiRealtimeCallTurnMapper;
import org.dromara.ai.mapper.AiWorkflowContextMapper;
import org.dromara.ai.realtime.AiRealtimeTtsConnectionRegistry;
import org.dromara.ai.realtime.AiRealtimeTtsAudioReadyEvent;
import org.dromara.ai.realtime.AiRealtimeTtsFallbackStartedEvent;
import org.dromara.ai.realtime.AiRealtimeTtsSegmentSentEvent;
import org.dromara.ai.workflow.AiWorkflowTemplateResolver;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private static final String ENDED_CALL_UUID_KEY_PREFIX = "callnexus:call:ended-uuid:";
    private static final Duration OWNERSHIP_TTL = Duration.ofMinutes(5);
    private static final Duration ENDED_CALL_TTL = Duration.ofSeconds(30);
    private static final String VAR_CUSTOMER_LEG_UUID = "callnexus_ai_customer_leg_uuid";
    private static final String VAR_ACTIVE = "callnexus_ai_active";
    private static final String VAR_BUSINESS_CALL_ID = "callnexus_business_call_id";
    private static final String VAR_AGENT_ID = "callnexus_ai_agent_id";
    private static final String VAR_FLOW_ID = "callnexus_ai_flow_id";
    private static final String VAR_NODE_ID = "callnexus_ai_node_id";
    private static final String VAR_TURN_ID = "callnexus_ai_turn_id";
    private static final String VAR_SEGMENT_SEQ = "callnexus_ai_segment_seq";
    private static final String VAR_GENERATION = "callnexus_ai_generation";
    private static final String VAR_OPENING_PREPLAYED = "callnexus_ai_opening_preplayed";
    private static final String SPEAKER_CUSTOMER = "CUSTOMER";
    private static final String SPEAKER_AI = "AI";
    private static final String SOURCE_REALTIME_ASR = "REALTIME_ASR";
    private static final String SOURCE_AI_GENERATED = "AI_GENERATED";
    private static final String TRANSCRIPT_STATUS_SUCCESS = "SUCCESS";
    private static final int MAX_MERGED_FOLLOWUP_LENGTH = 72;
    private static final int MAX_RECENT_RECOGNITION_EVENTS = 64;

    private final AiKnowledgeProperties properties;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final AiSpeechProviderSelector speechProviderSelector;
    private final AiAgentApplicationService agentService;
    private final AiIntentApplicationService intentService;
    private final AiWorkflowRuntimeService workflowRuntimeService;
    private final AiGeneratedMediaQueryService generatedMediaQueryService;
    private final AiWorkflowContextMapper workflowContextMapper;
    private final AiWorkflowTemplateResolver workflowTemplateResolver;
    private final AiAgentMapper agentMapper;
    private final AiRealtimeCallSessionMapper sessionMapper;
    private final AiRealtimeCallTurnMapper turnMapper;
    private final AiCallRecordingSourceMapper recordingSourceMapper;
    private final AiCallTranscriptMapper transcriptMapper;
    private final AiCallTranscriptSegmentMapper transcriptSegmentMapper;
    private final AiCallTranscriptStreamService transcriptStreamService;
    private final AiTicketDraftTriggerService ticketDraftTriggerService;
    private final AiRealtimeTtsInternalService realtimeTtsService;
    private final AiRealtimeTtsConnectionRegistry ttsConnectionRegistry;
    private final ObjectProvider<AiRealtimeTelephonyGateway> telephonyGatewayProvider;
    private final ObjectProvider<AiIntentTicketActionService> ticketActionServiceProvider;
    @Qualifier("aiRealtimeExecutor")
    private final Executor executor;
    @Qualifier("aiRealtimeIntentExecutor")
    private final ThreadPoolTaskExecutor intentExecutor;
    @Qualifier("aiRealtimeScheduler")
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<String, RuntimeSession> sessions = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();

    public AiRealtimeMrcpEventService(AiKnowledgeProperties properties,
                                      FreeSwitchNodeQueryService nodeQueryService,
                                      AiSpeechProviderSelector speechProviderSelector,
                                      AiAgentApplicationService agentService,
                                      AiIntentApplicationService intentService,
                                      AiWorkflowRuntimeService workflowRuntimeService,
                                      AiGeneratedMediaQueryService generatedMediaQueryService,
                                      AiWorkflowContextMapper workflowContextMapper,
                                      AiWorkflowTemplateResolver workflowTemplateResolver,
                                      AiAgentMapper agentMapper,
                                      AiRealtimeCallSessionMapper sessionMapper,
                                      AiRealtimeCallTurnMapper turnMapper,
                                      AiCallRecordingSourceMapper recordingSourceMapper,
                                       AiCallTranscriptMapper transcriptMapper,
                                       AiCallTranscriptSegmentMapper transcriptSegmentMapper,
                                       AiCallTranscriptStreamService transcriptStreamService,
                                       AiTicketDraftTriggerService ticketDraftTriggerService,
                                       AiRealtimeTtsInternalService realtimeTtsService,
                                       AiRealtimeTtsConnectionRegistry ttsConnectionRegistry,
                                       ObjectProvider<AiRealtimeTelephonyGateway> telephonyGatewayProvider,
                                      ObjectProvider<AiIntentTicketActionService> ticketActionServiceProvider,
                                      @Qualifier("aiRealtimeExecutor") Executor executor,
                                      @Qualifier("aiRealtimeIntentExecutor") ThreadPoolTaskExecutor intentExecutor,
                                      @Qualifier("aiRealtimeScheduler") ThreadPoolTaskScheduler scheduler) {
        this.properties = properties;
        this.nodeQueryService = nodeQueryService;
        this.speechProviderSelector = speechProviderSelector;
        this.agentService = agentService;
        this.intentService = intentService;
        this.workflowRuntimeService = workflowRuntimeService;
        this.generatedMediaQueryService = generatedMediaQueryService;
        this.workflowContextMapper = workflowContextMapper;
        this.workflowTemplateResolver = workflowTemplateResolver;
        this.agentMapper = agentMapper;
        this.sessionMapper = sessionMapper;
        this.turnMapper = turnMapper;
        this.recordingSourceMapper = recordingSourceMapper;
        this.transcriptMapper = transcriptMapper;
        this.transcriptSegmentMapper = transcriptSegmentMapper;
        this.transcriptStreamService = transcriptStreamService;
        this.ticketDraftTriggerService = ticketDraftTriggerService;
        this.realtimeTtsService = realtimeTtsService;
        this.ttsConnectionRegistry = ttsConnectionRegistry;
        this.telephonyGatewayProvider = telephonyGatewayProvider;
        this.ticketActionServiceProvider = ticketActionServiceProvider;
        this.executor = executor;
        this.intentExecutor = intentExecutor;
        this.scheduler = scheduler;
    }

    public void handle(Long nodeId, String eventName, String uuid, Map<String, String> headers) {
        long receivedAtNanos = System.nanoTime();
        if (!Boolean.TRUE.equals(properties.getRealtimeEnabled())) {
            return;
        }
        // Hangup events frequently omit application-specific channel variables. Resolve an existing
        // runtime first so a terminal event cannot be discarded by the UniMRCP context filter.
        if (isTerminal(eventName)) {
            List<RuntimeSession> terminalSessions = findTerminalSessions(nodeId, uuid, headers);
            for (RuntimeSession runtime : terminalSessions) {
                TenantHelper.dynamic(runtime.tenantId, () -> end(runtime, eventName));
            }
            if (!terminalSessions.isEmpty()) {
                log.info("AI UniMRCP 已按终止事件关闭实时会话，nodeId={}，eventName={}，uuid={}，sessionCount={}，hangupCause={}，sipDisposition={}",
                    nodeId, eventName, uuid, terminalSessions.size(),
                    firstNonBlank(header(headers, "Hangup-Cause"), header(headers, "variable_hangup_cause")),
                    header(headers, "variable_sip_hangup_disposition"));
            }
            return;
        }
        if ("false".equalsIgnoreCase(header(headers, VAR_ACTIVE))) {
            List<RuntimeSession> transferredSessions = findTerminalSessions(nodeId, uuid, headers);
            for (RuntimeSession runtime : transferredSessions) {
                TenantHelper.dynamic(runtime.tenantId, () -> end(runtime, "AI_HANDOFF_COMPLETE"));
            }
            log.debug("忽略已完成业务交接的 AI UniMRCP 事件，nodeId={}，eventName={}，uuid={}",
                nodeId, eventName, uuid);
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
        if (isEndedCall(ownershipUuid)) {
            log.debug("忽略已终止通道的迟到 AI UniMRCP 事件，nodeId={}，eventName={}，uuid={}，customerLegUuid={}",
                nodeId, eventName, uuid, ownershipUuid);
            return;
        }
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
        boolean openingParkEntered = isOpeningParkEntered(eventName, application);
        boolean mediaComplete = isMediaPlaybackComplete(eventName, application);
        RuntimeSession runtime = sessions.computeIfAbsent(key, ignored -> createRuntime(tenantId, nodeId, finalFlowId, finalIvrNodeId,
            finalAgentId, finalBusinessCallId, finalCustomerLegUuid, finalOpeningPreplayed));
        runtime.touch();
        ensureChannelProbe(runtime);
        String speechType = header(headers, SPEECH_TYPE_HEADER);
        String recognized = recognizedText(headers);
        if (StringUtils.isBlank(recognized) && "DETECTED_SPEECH".equals(eventName)
            && "detected-speech".equalsIgnoreCase(speechType)) {
            recognized = parseNlsmlText(header(headers, EVENT_BODY_HEADER));
        }
        if ((speakComplete || openingParkEntered) && runtime.openingPreplayed) {
            runtime.preplayedOpeningCompleted.set(true);
        }
        if (runtime.started.compareAndSet(false, true)) {
            if ("DETECTED_SPEECH".equals(eventName) && StringUtils.isNotBlank(recognized)) {
                LocalDateTime recognizedAt = LocalDateTime.now();
                runtime.deferRecognition(recognized.trim(), receivedAtNanos, recognizedAt, true);
                runtime.recognizing.set(false);
                appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_CUSTOMER, SOURCE_REALTIME_ASR,
                    recognized.trim(), recognizedAt, null);
                log.info("AI UniMRCP 首条识别结果已暂存，等待会话初始化后处理，sessionId={}，businessCallId={}，text={}",
                    runtime.entity.getId(), runtime.businessCallId, recognized.trim());
            }
            log.info("AI UniMRCP 首次完整事件已收到，准备异步启动会话，sessionId={}，businessCallId={}，customerLegUuid={}，eventName={}，application={}，applicationData={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, eventName, application, applicationData);
            executor.execute(() -> TenantHelper.dynamic(tenantId, () -> start(runtime)));
            return;
        }
        if (openingParkEntered && runtime.openingPreplayed) {
            completePreplayedOpeningOnPark(runtime);
            return;
        }
        if (speakComplete) {
            onSpeakComplete(runtime, applicationData,
                header(headers, VAR_TURN_ID),
                parseInteger(header(headers, VAR_SEGMENT_SEQ)),
                parseLong(header(headers, VAR_GENERATION)));
            return;
        }
        if (mediaComplete && runtime.workflowMediaPlayback.get()) {
            completeWorkflowMedia(runtime, "EVENT");
            return;
        }
        if ("DETECTED_SPEECH".equals(eventName) && "begin-speaking".equalsIgnoreCase(speechType)) {
            handleBargeInBegin(runtime);
            return;
        }
        if ("DETECTED_SPEECH".equals(eventName)) {
            runtime.openingRecognitionActive.set(false);
        }
        final String result = recognized;
        boolean detectedSpeech = "DETECTED_SPEECH".equals(eventName);
        if (detectedSpeech && StringUtils.isBlank(result)) {
            if (runtime.controlResumeInProgress.get()) {
                runtime.recognizing.set(false);
                log.debug("AI UniMRCP 控场期间忽略空识别结果，sessionId={}，businessCallId={}",
                    runtime.entity.getId(), runtime.businessCallId);
                return;
            }
            log.warn("收到 DETECTED_SPEECH 但未找到识别文本，sessionId={}，businessCallId={}，候选字段={}，speech相关事件头={}",
                runtime.entity.getId(), runtime.businessCallId, properties.getUnimrcp().getResultHeaderCandidates(),
                speechRelatedHeaders(headers));
            handleEmptyRecognition(runtime);
            return;
        }
        String recognitionEventId = detectedSpeech ? recognitionEventKey(headers) : null;
        if (StringUtils.isNotBlank(result) && !runtime.claimRecognitionEvent(recognitionEventId)) {
            log.debug("Ignoring duplicate final speech event, sessionId={}, businessCallId={}, eventId={}, text={}",
                runtime.entity.getId(), runtime.businessCallId, recognitionEventId, result.trim());
            return;
        }
        // 控场只能由播报期间的 begin-speaking 事件触发。最终识别结果可能晚于实际播放结束，
        // 不能再拿残留的 activeSpeak 状态重新判定，否则正常轮到客户说话也会误播控场话术。
        if (StringUtils.isNotBlank(result) && runtime.controlResumeInProgress.get()) {
            runtime.recognizing.set(false);
            appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_CUSTOMER, SOURCE_REALTIME_ASR,
                result.trim(), LocalDateTime.now(), null);
            log.info("AI UniMRCP 控场期间忽略本次插话内容，等待原播报完成后重新收音，sessionId={}，businessCallId={}，text={}",
                runtime.entity.getId(), runtime.businessCallId, result.trim());
            return;
        }
        if (StringUtils.isNotBlank(result) && isBargeInPlayback(runtime)) {
            interruptCurrentOutput(runtime, "FINAL_SPEECH");
        }
        if (StringUtils.isNotBlank(result) && runtime.acceptRecognition()) {
            long recognitionAcceptedNanos = System.nanoTime();
            runtime.consecutiveEmptyRecognitions.set(0);
            runtime.recognizing.set(false);
            LocalDateTime recognizedAt = LocalDateTime.now();
            processAcceptedRecognition(runtime, result.trim(), recognitionAcceptedNanos);
            appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_CUSTOMER, SOURCE_REALTIME_ASR,
                result.trim(), recognizedAt, null);
        } else if (StringUtils.isNotBlank(result)) {
            LocalDateTime recognizedAt = LocalDateTime.now();
            runtime.deferRecognition(result.trim(), receivedAtNanos, recognizedAt, true);
            runtime.recognizing.set(false);
            appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_CUSTOMER, SOURCE_REALTIME_ASR,
                result.trim(), recognizedAt, null);
            log.info("AI UniMRCP 当前轮次尚未释放，识别结果已暂存，sessionId={}，businessCallId={}，text={}",
                runtime.entity.getId(), runtime.businessCallId, result.trim());
        }
    }

    private void processAcceptedRecognition(RuntimeSession runtime, String text, long recognitionAcceptedNanos) {
        log.info("AI UniMRCP 识别到用户语音，sessionId={}，businessCallId={}，text={}",
            runtime.entity.getId(), runtime.businessCallId, text);
        executor.execute(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (runtime.workflowActive.get()) {
                processWorkflowInput(runtime, text);
            } else {
                processTurn(runtime, text, recognitionAcceptedNanos);
            }
        }));
    }

    private boolean processDeferredRecognition(RuntimeSession runtime) {
        DeferredRecognition deferred = runtime.pollDeferredRecognition();
        if (deferred == null || runtime.closed.get()) {
            return false;
        }
        if (!runtime.acceptRecognition()) {
            runtime.deferRecognition(deferred.text(), deferred.receivedAtNanos(), deferred.recognizedAt(),
                deferred.transcriptPersisted());
            return false;
        }
        runtime.consecutiveEmptyRecognitions.set(0);
        runtime.recognizing.set(false);
        log.info("AI UniMRCP 开始处理暂存的识别结果，sessionId={}，businessCallId={}，text={}",
            runtime.entity.getId(), runtime.businessCallId, deferred.text());
        processAcceptedRecognition(runtime, deferred.text(), deferred.receivedAtNanos());
        if (!deferred.transcriptPersisted()) {
            appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_CUSTOMER, SOURCE_REALTIME_ASR,
                deferred.text(), deferred.recognizedAt(), null);
        }
        return true;
    }

    private void resumeListening(RuntimeSession runtime) {
        markListening(runtime);
        if (!processDeferredRecognition(runtime)) {
            tryRecognize(runtime);
        }
    }

    private void handleEmptyRecognition(RuntimeSession runtime) {
        if (runtime.closed.get()) {
            return;
        }
        if (!runtime.recognizing.compareAndSet(true, false)) {
            log.debug("忽略同一识别轮次的重复空结果，sessionId={}，businessCallId={}，customerLegUuid={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid);
            return;
        }
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
        int emptyCount = runtime.consecutiveEmptyRecognitions.incrementAndGet();
        Integer configuredMaxEmptyCount = properties.getUnimrcp().getMaxConsecutiveEmptyRecognitions();
        int maxEmptyCount = Math.max(1, configuredMaxEmptyCount == null ? 3 : configuredMaxEmptyCount);
        if (emptyCount >= maxEmptyCount) {
            log.warn("AI UniMRCP 连续空识别达到上限，关闭残留客户通道，sessionId={}，businessCallId={}，customerLegUuid={}，emptyCount={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, emptyCount);
            if (Boolean.TRUE.equals(properties.getUnimrcp().getHangupOnRecognitionIdle())) {
                try {
                    gateway().hangup(runtime.nodeId, runtime.customerLegUuid);
                } catch (Exception exception) {
                    log.warn("AI UniMRCP 空识别超时关闭客户通道失败，仍结束 AI 会话，sessionId={}，businessCallId={}，customerLegUuid={}，error={}",
                        runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, exception.getMessage());
                }
            }
            end(runtime, "CONSECUTIVE_EMPTY_RECOGNITION");
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
                if (isBargeInPlayback(runtime)) {
                    ActiveSpeak active = runtime.activeSpeak.get();
                    if (active != null) {
                        tryRecognizeDuringPlayback(runtime, active.opening());
                    }
                } else {
                    resumeListening(runtime);
                }
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
            agent == null ? null : agent.getAgentName(), ttsProvider.getDefaultVoice(), entity,
            openingPreplayed, transport, wsUrl,
            agent != null && Boolean.TRUE.equals(agent.getBargeInEnabled()),
            agent != null && Boolean.TRUE.equals(agent.getOpeningBargeInEnabled()),
            agent == null ? "STANDARD" : agent.getBargeInMode(),
            agent == null || agent.getBargeInGraceMs() == null ? 500 : agent.getBargeInGraceMs(),
            agent == null ? "INTERRUPT" : agent.getBargeInStrategy(),
            agent == null ? null : agent.getBargeInControlText(),
            agent == null || agent.getBargeInControlMaxCount() == null ? 2 : agent.getBargeInControlMaxCount(),
            agent == null || agent.getBargeInControlCooldownMs() == null ? 5000 : agent.getBargeInControlCooldownMs());
    }

    private AiSpeechProvider defaultRealtimeTtsProvider() {
        try {
            return speechProviderSelector.requireDefaultStreamingTts();
        } catch (ServiceException exception) {
            return speechProviderSelector.requireDefaultTts();
        }
    }

    private void start(RuntimeSession runtime) {
        if (runtime.closed.get()) {
            return;
        }
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
            Map<String, Object> workflowVariables = new LinkedHashMap<>();
            workflowVariables.put("call.businessCallId", runtime.businessCallId);
            workflowVariables.put("call.customerLegUuid", runtime.customerLegUuid);
            workflowVariables.put("agent.id", runtime.agentId);
            putIfNotBlank(workflowVariables, "agent.name", runtime.agentName);
            String workflowScene = enrichWorkflowVariables(runtime, workflowVariables);
            workflowVariables.put("call.direction", "VOICE_OUTBOUND".equals(workflowScene) ? "OUTBOUND" : "INBOUND");
            runtime.templateVariables = Map.copyOf(workflowVariables);
            try {
                var workflow = workflowRuntimeService.startVoice(runtime.agentId, runtime.businessCallId,
                    workflowScene, workflowVariables);
                if (workflow.isPresent()) {
                    runtime.workflowExecutionId = workflow.get().getExecutionId();
                    runtime.workflowActive.set(true);
                    runtime.conversationReady.set(true);
                    handleWorkflowAction(runtime, workflow.get());
                    log.info("AI UniMRCP 已启动语音工作流，sessionId={}，businessCallId={}，executionId={}，actionType={}",
                        runtime.entity.getId(), runtime.businessCallId, runtime.workflowExecutionId,
                        workflow.get().getActionType());
                    return;
                }
            } catch (Exception exception) {
                log.warn("AI UniMRCP 启动语音工作流失败，回退默认 AI 对话，sessionId={}，businessCallId={}，agentId={}，error={}",
                    runtime.entity.getId(), runtime.businessCallId, runtime.agentId, exception.getMessage(), exception);
                String failedExecutionId = runtime.workflowExecutionId;
                runtime.workflowExecutionId = null;
                runtime.workflowActive.set(false);
                runtime.workflowSpeak.set(false);
                if (StringUtils.isNotBlank(failedExecutionId)) {
                    try {
                        workflowRuntimeService.terminate(failedExecutionId, "VOICE_START_ACTION_FAILED");
                    } catch (Exception terminateException) {
                        log.debug("AI 语音工作流启动失败后终止实例失败，executionId={}，error={}",
                            failedExecutionId, terminateException.getMessage());
                    }
                }
            }
            long conversationNanos = System.nanoTime();
            AiConversationStartResponse start = agentService.startRealtimeConversation(runtime.agentId);
            long conversationCostMs = elapsedMillis(conversationNanos);
            if (runtime.closed.get()) {
                return;
            }
            long updateNanos = System.nanoTime();
            runtime.entity.setConversationId(Long.valueOf(String.valueOf(start.getConversation().getId())));
            sessionMapper.updateById(runtime.entity);
            long updateCostMs = elapsedMillis(updateNanos);
            runtime.conversationReady.set(true);
            String openingText = start.getMessage() == null ? null : start.getMessage().getContent();
            runtime.lastAssistantText = openingText;
            if (StringUtils.isBlank(openingText)) {
                runtime.waitingSpeakComplete.set(false);
                resumeListening(runtime);
            } else {
                appendRealtimeTranscriptSegment(runtime, SPEAKER_AI, SOURCE_AI_GENERATED, openingText,
                    LocalDateTime.now(), runtime.agentId);
                if (runtime.openingPreplayed) {
                    waitForPreplayedOpening(runtime, openingText);
                } else {
                    speak(runtime, openingText, null);
                }
            }
            log.info("AI UniMRCP 会话已建立，sessionId={}，businessCallId={}，customerLegUuid={}，agentId={}，conversationCostMs={}，updateCostMs={}，totalCostMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, runtime.agentId,
                conversationCostMs, updateCostMs, elapsedMillis(startNanos));
        } catch (Exception exception) {
            fail(runtime, "AI UniMRCP 会话启动失败：" + exception.getMessage(), exception);
        }
    }

    private void processTurn(RuntimeSession runtime, String text, long recognitionAcceptedNanos) {
        if (runtime.closed.get() || "ENDING".equals(runtime.entity.getSessionState())
            || "TRANSFERRING".equals(runtime.entity.getSessionState())) {
            return;
        }
        long processStartedNanos = System.nanoTime();
        long generation = runtime.turnGeneration.incrementAndGet();
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

        if (handlePendingIntentConfirmation(runtime, turn, text)
            || recognizeAndHandleIntent(runtime, turn, text)) {
            return;
        }
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
            if (runtime.closed.get() || generation != runtime.turnGeneration.get()) {
                return;
            }
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
                        enqueueSpeak(runtime, sentence, generation);
                        appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_AI, SOURCE_AI_GENERATED,
                            sentence, LocalDateTime.now(), runtime.agentId);
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
            if (runtime.closed.get()) {
                cancelTurnAfterHangup(runtime, turn);
                return;
            }
            if (generation != runtime.turnGeneration.get()) {
                markTurnInterrupted(turn, "BARGE_IN");
                return;
            }
            String tail = runtime.segmenter.drain();
            if (StringUtils.isNotBlank(tail)) {
                if (firstSpeakReadyNanos.compareAndSet(0L, System.nanoTime())) {
                    log.info("AI UniMRCP 首个可播句在模型结束时生成，sessionId={}，businessCallId={}，turn={}，recognizedToWorkerMs={}，prepareMs={}，firstDeltaMs={}，firstSpeakReadyMs={}，segmentLength={}",
                        runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(),
                        (processStartedNanos - recognitionAcceptedNanos) / 1_000_000L, prepareCostMs,
                        firstDeltaNanos.get() == 0L ? null : (firstDeltaNanos.get() - chatNanos) / 1_000_000L,
                        (firstSpeakReadyNanos.get() - chatNanos) / 1_000_000L, tail.length());
                }
                enqueueSpeak(runtime, tail, generation);
                appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_AI, SOURCE_AI_GENERATED,
                    tail, LocalDateTime.now(), runtime.agentId);
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
            runtime.lastAssistantText = fullAnswer.toString();
            if ("THINKING".equals(turn.getTurnState())) {
                turn.setTurnState("SPEAKING");
            }
            turnMapper.updateById(turn);
            sessionMapper.updateById(runtime.entity);
            runtime.llmStreaming.set(false);
            prewarmPendingSegments(runtime);
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
            if (generation != runtime.turnGeneration.get()) {
                markTurnInterrupted(turn, "BARGE_IN");
                return;
            }
            if (runtime.closed.get()) {
                cancelTurnAfterHangup(runtime, turn);
                return;
            }
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
            log.error("AI UniMRCP 轮次处理失败，sessionId={}，turn={}，text={}，error={}，chatCostMs={}",
                runtime.entity.getId(), turn.getSequenceNo(), text, exception.getMessage(), elapsedMillis(chatNanos), exception);
            resumeListening(runtime);
        }
    }

    private boolean recognizeAndHandleIntent(RuntimeSession runtime, AiRealtimeCallTurn turn, String text) {
        AiIntentRecognitionResponse recognition;
        long startedNanos = System.nanoTime();
        long timeoutMs = resolveRealtimeIntentTimeout(properties.getRealtimeIntentTimeoutMs());
        Future<AiIntentRecognitionResponse> intentFuture = null;
        try {
            AiIntentRecognitionRequest request = new AiIntentRecognitionRequest();
            request.setAgentId(runtime.agentId);
            request.setText(text);
            intentFuture = intentExecutor.submit(() -> TenantHelper.dynamic(runtime.tenantId,
                () -> intentService.recognize(request)));
            recognition = intentFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            if (intentFuture != null) {
                intentFuture.cancel(true);
            }
            log.warn("AI intent recognition exceeded realtime budget; continuing with knowledge/model answer, sessionId={}, businessCallId={}, turn={}, timeoutMs={}, costMs={}",
                runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(), timeoutMs,
                elapsedMillis(startedNanos));
            return false;
        } catch (InterruptedException exception) {
            if (intentFuture != null) {
                intentFuture.cancel(true);
            }
            Thread.currentThread().interrupt();
            log.warn("AI intent recognition interrupted; continuing with knowledge/model answer, sessionId={}, businessCallId={}, turn={}, costMs={}",
                runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(), elapsedMillis(startedNanos));
            return false;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            log.warn("AI intent recognition failed; continuing with knowledge/model answer, sessionId={}, businessCallId={}, error={}",
                runtime.entity.getId(), runtime.businessCallId, cause.getMessage());
            return false;
        } catch (Exception exception) {
            log.warn("AI intent recognition failed; continuing with knowledge/model answer, sessionId={}, businessCallId={}, error={}",
                runtime.entity.getId(), runtime.businessCallId, exception.getMessage());
            return false;
        }
        log.info("AI intent recognition completed, sessionId={}, businessCallId={}, turn={}, matched={}, intentCode={}, actionType={}, matchMethod={}, confidence={}, costMs={}",
            runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(), recognition.isMatched(),
            recognition.getIntentCode(), recognition.getActionType(), recognition.getMatchMethod(),
            recognition.getConfidence(), elapsedMillis(startedNanos));
        if (!recognition.isMatched()) {
            return false;
        }
        String actionType = StringUtils.blankToDefault(recognition.getActionType(), "NONE").toUpperCase(Locale.ROOT);
        if ("NONE".equals(actionType) || "KNOWLEDGE_QUERY".equals(actionType)) {
            return false;
        }
        if ("CHAT_REPLY".equals(actionType)) {
            if (StringUtils.isBlank(recognition.getResponseTemplate())) {
                return false;
            }
            completeIntentTurn(runtime, turn, recognition.getResponseTemplate(), "INTENT_TEMPLATE", null);
            return true;
        }
        if ("REPEAT_LAST_REPLY".equals(actionType)) {
            if (StringUtils.isBlank(runtime.lastAssistantText)) {
                return false;
            }
            completeIntentTurn(runtime, turn, runtime.lastAssistantText, "INTENT_REPEAT", null);
            return true;
        }

        PendingIntentAction action;
        try {
            action = pendingAction(recognition);
        } catch (Exception exception) {
            log.error("AI intent action configuration is invalid, sessionId={}, businessCallId={}, intentCode={}, actionType={}, error={}",
                runtime.entity.getId(), runtime.businessCallId, recognition.getIntentCode(), actionType, exception.getMessage());
            completeIntentTurn(runtime, turn, "当前操作配置不可用，请稍后再试。", "INTENT_ERROR", null);
            return true;
        }
        if (action == null) {
            return false;
        }
        if (Boolean.TRUE.equals(recognition.getConfirmationRequired())) {
            runtime.pendingConfirmation.set(action);
            completeIntentTurn(runtime, turn, confirmationPrompt(recognition), "INTENT_CONFIRMATION", null);
            return true;
        }
        completeIntentTurn(runtime, turn, actionReply(recognition), "INTENT_ACTION", action);
        return true;
    }

    private boolean handlePendingIntentConfirmation(RuntimeSession runtime, AiRealtimeCallTurn turn, String text) {
        PendingIntentAction pending = runtime.pendingConfirmation.get();
        if (pending == null) {
            return false;
        }
        String normalized = normalizeConfirmation(text);
        if (isConfirmation(normalized)) {
            runtime.pendingConfirmation.compareAndSet(pending, null);
            String reply = StringUtils.isNotBlank(pending.responseTemplate())
                ? pending.responseTemplate() : defaultActionReply(pending.actionType());
            completeIntentTurn(runtime, turn, reply, "INTENT_CONFIRMED", pending);
            return true;
        }
        if (isCancellation(normalized)) {
            runtime.pendingConfirmation.compareAndSet(pending, null);
            completeIntentTurn(runtime, turn, "已取消本次操作。", "INTENT_CANCELLED", null);
            return true;
        }
        completeIntentTurn(runtime, turn, "请回答确认或取消。", "INTENT_CONFIRMATION", null);
        return true;
    }

    private PendingIntentAction pendingAction(AiIntentRecognitionResponse recognition) throws Exception {
        String actionType = StringUtils.blankToDefault(recognition.getActionType(), "NONE").toUpperCase(Locale.ROOT);
        String target = null;
        String actionConfigJson = recognition.getActionConfigJson();
        if ("TRANSFER_EXTENSION".equals(actionType)
            || "TRANSFER_QUEUE".equals(actionType)
            || "TRANSFER_IVR".equals(actionType)) {
            JsonNode config = JsonUtils.getObjectMapper().readTree(actionConfigJson);
            target = switch (actionType) {
                case "TRANSFER_EXTENSION" -> config.path("extension").asText(null);
                case "TRANSFER_QUEUE" -> config.path("queueCode").asText(null);
                case "TRANSFER_IVR" -> config.path("ivrFlowId").asText(null);
                default -> null;
            };
            if (StringUtils.isBlank(target)) {
                throw new ServiceException("Missing transfer target");
            }
        }
        return switch (actionType) {
            case "STOP_PLAYBACK", "TRANSFER_EXTENSION", "TRANSFER_QUEUE", "TRANSFER_IVR", "CREATE_TICKET", "END_CALL" ->
                new PendingIntentAction(recognition.getIntentCode(), recognition.getIntentName(), actionType,
                    target, recognition.getResponseTemplate(), actionConfigJson);
            default -> null;
        };
    }

    private void completeIntentTurn(RuntimeSession runtime, AiRealtimeCallTurn turn, String reply,
                                    String answerSource, PendingIntentAction action) {
        runtime.llmStreaming.set(false);
        LocalDateTime answeredAt = LocalDateTime.now();
        String answer = StringUtils.blankToDefault(reply, "");
        turn.setAssistantText(answer);
        turn.setAnswerSource(answerSource);
        turn.setAnsweredAt(answeredAt);
        turn.setTurnState(StringUtils.isBlank(answer) ? "COMPLETED" : "SPEAKING");
        turnMapper.updateById(turn);
        if (StringUtils.isNotBlank(answer)) {
            runtime.lastAssistantText = answer;
            appendRealtimeTranscriptSegment(runtime, SPEAKER_AI, SOURCE_AI_GENERATED, answer, answeredAt, runtime.agentId);
        }
        if (action != null) {
            runtime.postPlaybackAction.set(action);
        }
        log.info("AI intent handled in realtime call, sessionId={}, businessCallId={}, turn={}, answerSource={}, actionType={}, target={}",
            runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(), answerSource,
            action == null ? null : action.actionType(), action == null ? null : action.target());
        if (StringUtils.isNotBlank(answer)) {
            enqueueSpeak(runtime, answer);
            return;
        }
        runtime.currentTurn.compareAndSet(turn, null);
        finishTurn(runtime, turn);
    }

    private String confirmationPrompt(AiIntentRecognitionResponse recognition) {
        String name = StringUtils.blankToDefault(recognition.getIntentName(), "当前操作");
        return "请确认是否执行" + name + "，请回答确认或取消。";
    }

    private String actionReply(AiIntentRecognitionResponse recognition) {
        return StringUtils.isNotBlank(recognition.getResponseTemplate())
            ? recognition.getResponseTemplate() : defaultActionReply(recognition.getActionType());
    }

    private String defaultActionReply(String actionType) {
        return switch (StringUtils.blankToDefault(actionType, "").toUpperCase(Locale.ROOT)) {
            case "TRANSFER_EXTENSION", "TRANSFER_QUEUE", "TRANSFER_IVR" -> "正在为您转接，请稍候。";
            case "END_CALL" -> "感谢您的来电，再见。";
            case "STOP_PLAYBACK" -> "好的。";
            default -> "好的，正在为您处理。";
        };
    }

    private String normalizeConfirmation(String text) {
        return StringUtils.blankToDefault(text, "").toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private boolean isConfirmation(String text) {
        return Set.of("确认", "确定", "是", "是的", "好的", "好", "可以", "同意", "执行").contains(text);
    }

    private boolean isCancellation(String text) {
        return Set.of("取消", "不用", "不要", "否", "不是", "不同意", "算了", "停止").contains(text);
    }

    private void waitForPreplayedOpening(RuntimeSession runtime, String openingText) {
        updateState(runtime, "SPEAKING", null);
        runtime.activeSpeak.compareAndSet(null,
            new ActiveSpeak("opening", 1, openingText, System.nanoTime(), runtime.turnGeneration.get(), true, false));
        if (runtime.preplayedOpeningCompleted.get() || !runtime.waitingSpeakComplete.get()) {
            runtime.activeSpeak.set(null);
            listenAfterOpening(runtime);
            return;
        }
        long delay = speakCompletionTimeout(runtime, openingText);
        ScheduledFuture<?> old = runtime.pendingSpeakTimer.getAndSet(null);
        if (old != null) {
            old.cancel(false);
        }
        ScheduledFuture<?> handle = scheduler.schedule(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (!runtime.waitingSpeakComplete.compareAndSet(true, false)) {
                return;
            }
            if (!ensureChannelAlive(runtime, "CHANNEL_GONE_BEFORE_OPENING_TIMEOUT")) {
                return;
            }
            log.warn("AI UniMRCP 长时间未收到首句播报完成事件，按安全超时启动识别，sessionId={}，businessCallId={}，customerLegUuid={}，timeoutMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, delay);
            listenAfterOpening(runtime);
        }), Instant.now().plusMillis(delay));
        runtime.pendingSpeakTimer.set(handle);
        log.info("AI UniMRCP 首句已由 dialplan 播放，等待播报完成后启动识别，sessionId={}，businessCallId={}，customerLegUuid={}，delayMs={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, delay);
    }

    private void speak(RuntimeSession runtime, String text, AiRealtimeCallTurn turn) {
        speak(runtime, text, turn, turn == null);
    }

    private String enrichWorkflowVariables(RuntimeSession runtime, Map<String, Object> variables) {
        if (StringUtils.isBlank(runtime.businessCallId)) {
            return "VOICE_INBOUND";
        }
        try {
            AiWorkflowCustomerContext customer = workflowContextMapper.findOutboundCustomer(
                runtime.tenantId, runtime.businessCallId);
            String sceneType = customer == null ? "VOICE_INBOUND" : "VOICE_OUTBOUND";
            if (customer == null) {
                customer = workflowContextMapper.findInboundCustomer(runtime.tenantId, runtime.businessCallId);
            }
            if (customer == null) {
                return sceneType;
            }
            putIfNotBlank(variables, "call.callerNumber", customer.getPhone());
            putIfNotBlank(variables, "customer.phone", customer.getPhone());
            if (customer.getCustomerId() != null) {
                variables.put("customer.id", customer.getCustomerId());
            }
            putIfNotBlank(variables, "customer.name", customer.getCustomerName());
            appendCustomerFormVariables(variables, customer.getFormData());
            if (customer.getOutboundTaskId() != null) {
                variables.put("outbound.taskId", customer.getOutboundTaskId());
                putIfNotBlank(variables, "outbound.taskName", customer.getOutboundTaskName());
                putIfNotBlank(variables, "task.name", customer.getOutboundTaskName());
            }
            if (customer.getOutboundMemberId() != null) {
                variables.put("outbound.memberId", customer.getOutboundMemberId());
            }
            if (customer.getOutboundAttemptCount() != null) {
                variables.put("outbound.attemptCount", customer.getOutboundAttemptCount());
            }
            log.info("AI 语音工作流已装载通话上下文，sessionId={}，businessCallId={}，sceneType={}，customerId={}，taskId={}，memberId={}，hasName={}，phone={}",
                runtime.entity.getId(), runtime.businessCallId, sceneType, customer.getCustomerId(),
                customer.getOutboundTaskId(), customer.getOutboundMemberId(),
                StringUtils.isNotBlank(customer.getCustomerName()), customer.getPhone());
            return sceneType;
        } catch (Exception exception) {
            log.warn("AI 语音工作流装载通话上下文失败，继续按呼入场景使用基础变量，sessionId={}，businessCallId={}，error={}",
                runtime.entity.getId(), runtime.businessCallId, exception.getMessage());
            return "VOICE_INBOUND";
        }
    }

    @SuppressWarnings("unchecked")
    private void appendCustomerFormVariables(Map<String, Object> variables, String formData) {
        if (StringUtils.isBlank(formData)) {
            return;
        }
        Map<String, Object> values = JsonUtils.parseObject(formData, LinkedHashMap.class);
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> {
            if (StringUtils.isNotBlank(key) && value != null) {
                variables.put("customer.custom." + key, value);
            }
        });
        Object gender = firstValue(values, "gender", "sex", "customerGender");
        if (gender != null && StringUtils.isNotBlank(String.valueOf(gender))) {
            variables.put("customer.gender", normalizeGender(String.valueOf(gender)));
        }
    }

    private Object firstValue(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return value;
            }
        }
        return null;
    }

    private String normalizeGender(String value) {
        String normalized = value.trim();
        if ("男".equals(normalized) || "M".equalsIgnoreCase(normalized) || "MALE".equalsIgnoreCase(normalized)) {
            return "MALE";
        }
        if ("女".equals(normalized) || "F".equalsIgnoreCase(normalized) || "FEMALE".equalsIgnoreCase(normalized)) {
            return "FEMALE";
        }
        return normalized;
    }

    private void putIfNotBlank(Map<String, Object> variables, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            variables.put(key, value);
        }
    }

    private void speak(RuntimeSession runtime, String text, AiRealtimeCallTurn turn, boolean opening) {
        speak(runtime, text, turn, opening, false);
    }

    private void speak(RuntimeSession runtime, String text, AiRealtimeCallTurn turn, boolean opening, boolean control) {
        if (runtime.closed.get()) {
            return;
        }
        if (!ensureChannelAlive(runtime, "CHANNEL_GONE_BEFORE_SPEAK")) {
            return;
        }
        if (StringUtils.isBlank(text)) {
            markListening(runtime);
            return;
        }
        long speakNanos = System.nanoTime();
        updateState(runtime, "SPEAKING", null);
        long stateCostMs = elapsedMillis(speakNanos);
        runtime.waitingSpeakComplete.set(true);
        if (!runtime.bargeInEnabled) {
            runtime.recognizing.set(false);
        }
        if (turn != null && !"SPEAKING".equals(turn.getTurnState()) && !"COMPLETED".equals(turn.getTurnState())) {
            turn.setTurnState("SPEAKING");
            turn.setPlaybackStartedAt(LocalDateTime.now());
            turnMapper.updateById(turn);
        }
        long gatewayNanos = System.nanoTime();
        int seq = runtime.speakSegmentSeq.incrementAndGet();
        String turnId = resolveTurnId(runtime, turn);
        boolean turnEnd = isTurnEnd(runtime, turn);
        ActiveSpeak activeSpeak = new ActiveSpeak(turnId, seq, text, System.nanoTime(),
            runtime.turnGeneration.get(), opening, control);
        runtime.activeSpeak.set(activeSpeak);
        runtime.audioReadySpeak.set(null);
        log.info("AI UniMRCP 准备提交播报，sessionId={}，businessCallId={}，customerLegUuid={}，textLength={}，voice={}，turnId={}，seq={}，turnEnd={}，stateCostMs={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, text.length(), runtime.ttsVoice,
            turnId, seq, turnEnd, stateCostMs);
        gateway().speak(runtime.nodeId, runtime.customerLegUuid, text, runtime.ttsVoice,
            turnId, seq, activeSpeak.generation(), turnEnd);
        long gatewayCostMs = elapsedMillis(gatewayNanos);
        long delay = speakCompletionTimeout(runtime, text);
        scheduleSpeakTimeout(runtime, activeSpeak, delay, "SUBMITTED");
        log.info("AI UniMRCP 已提交播报，sessionId={}，businessCallId={}，customerLegUuid={}，textLength={}，delayMs={}，gatewayCostMs={}，totalCostMs={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, text.length(), delay,
            gatewayCostMs, elapsedMillis(speakNanos));
    }

    private void scheduleSpeakTimeout(RuntimeSession runtime, ActiveSpeak activeSpeak, long delay, String phase) {
        ScheduledFuture<?> old = runtime.pendingSpeakTimer.getAndSet(null);
        if (old != null) {
            old.cancel(false);
        }
        ScheduledFuture<?> handle = scheduler.schedule(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (runtime.closed.get()) {
                return;
            }
            if (runtime.activeSpeak.get() != activeSpeak) {
                return;
            }
            if (!ensureChannelAlive(runtime, "CHANNEL_GONE_BEFORE_SPEAK_TIMEOUT")) {
                return;
            }
            if (shouldFallbackBeforeCompleting(runtime.voiceTransport,
                runtime.audioReadySpeak.get() == activeSpeak)) {
                log.warn("AI UniMRCP 实时 TTS 等待超时且未收到首段音频，当前通话降级 HTTP 并重播，sessionId={}，businessCallId={}，customerLegUuid={}，phase={}，timeoutMs={}",
                    runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, phase, delay);
                recoverPrematureWsCompletion(runtime, activeSpeak);
                return;
            }
            log.warn("AI UniMRCP 播报阶段超时，执行有界取消并恢复后续收音，sessionId={}，businessCallId={}，customerLegUuid={}，turnId={}，seq={}，generation={}，phase={}，timeoutMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid,
                activeSpeak.turnId(), activeSpeak.seq(), activeSpeak.generation(), phase, delay);
            recoverTimedOutSpeak(runtime, activeSpeak, delay, phase);
        }), Instant.now().plusMillis(delay));
        runtime.pendingSpeakTimer.set(handle);
    }

    /**
     * 解析下发给插件的轮次标识：正式轮次用 turn 序号，开场白用固定 "opening"。
     * 同一轮多段返回一致的 turnId，插件据此复用同一条 TTS WebSocket。
     */
    private String resolveTurnId(RuntimeSession runtime, AiRealtimeCallTurn turn) {
        if (turn != null && turn.getSequenceNo() != null) {
            return "turn-" + turn.getSequenceNo();
        }
        if (runtime.workflowStreamingReply.get() && StringUtils.isNotBlank(runtime.workflowStreamTurnId)) {
            return runtime.workflowStreamTurnId;
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
        if (turn == null && !runtime.workflowStreamingReply.get()) {
            return true;
        }
        if (runtime.llmStreaming.get()) {
            return false;
        }
        synchronized (runtime.pendingSpeakSegments) {
            return runtime.pendingSpeakSegments.isEmpty();
        }
    }

    private void onSpeakComplete(RuntimeSession runtime, String applicationData,
                                 String eventTurnId, Integer eventSeq, Long eventGeneration) {
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
        if (!shouldAcceptSpeakCompletion(activeSpeak, eventTurnId, eventSeq, eventGeneration, applicationData)) {
            log.warn("AI UniMRCP 忽略迟到或不匹配的播报完成事件，sessionId={}，businessCallId={}，activeTurnId={}，activeSeq={}，activeGeneration={}，eventTurnId={}，eventSeq={}，eventGeneration={}，applicationData={}",
                runtime.entity.getId(), runtime.businessCallId,
                activeSpeak.turnId(), activeSpeak.seq(), activeSpeak.generation(),
                eventTurnId, eventSeq, eventGeneration, applicationData);
            return;
        }
        if (shouldFallbackBeforeCompleting(runtime.voiceTransport,
            runtime.audioReadySpeak.get() == activeSpeak)) {
            recoverPrematureWsCompletion(runtime, activeSpeak);
            return;
        }
        completeSpeak(runtime, activeSpeak, "EVENT");
    }

    @EventListener
    public void onTtsAudioReady(AiRealtimeTtsAudioReadyEvent event) {
        if (event == null || StringUtils.isBlank(event.tenantId()) || StringUtils.isBlank(event.callId())) {
            return;
        }
        RuntimeSession runtime = sessions.get(key(event.tenantId(), event.callId()));
        if (runtime == null || runtime.closed.get()) {
            return;
        }
        ActiveSpeak active = runtime.activeSpeak.get();
        if (active == null || !matchesAudioReady(active, event.turnId(), event.sequence())) {
            log.debug("AI UniMRCP 忽略迟到或不匹配的 audio_ready，sessionId={}，businessCallId={}，activeTurnId={}，activeSeq={}，eventTurnId={}，eventSeq={}",
                runtime.entity.getId(), runtime.businessCallId,
                active == null ? null : active.turnId(), active == null ? null : active.seq(),
                event.turnId(), event.sequence());
            return;
        }
        runtime.audioReadySpeak.set(active);
        executor.execute(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (runtime.activeSpeak.get() != active || runtime.closed.get()) {
                return;
            }
            if (runtime.httpFallbackPending.compareAndSet(true, false)) {
                runtime.voiceTransport = VoiceTransport.HTTP;
                runtime.httpFallbackActivated.set(true);
                try {
                    gateway().applyVoiceTransport(runtime.nodeId, runtime.customerLegUuid, VoiceTransport.HTTP, null);
                    log.info("AI UniMRCP HTTP 降级音频已可播放，本通电话后续保持 HTTP TTS，sessionId={}，businessCallId={}",
                        runtime.entity.getId(), runtime.businessCallId);
                } catch (Exception exception) {
                    log.warn("AI UniMRCP 固化 HTTP TTS 模式失败，当前音频仍继续播放，sessionId={}，businessCallId={}，error={}",
                        runtime.entity.getId(), runtime.businessCallId, exception.getMessage());
                }
            }
            log.info("AI UniMRCP 已确认 TTS 首段音频可播放，允许按打断策略启动识别，sessionId={}，businessCallId={}，turnId={}，seq={}",
                runtime.entity.getId(), runtime.businessCallId, active.turnId(), active.seq());
            long playbackTimeout = audioReadyCompletionTimeout(runtime, active.text());
            scheduleSpeakTimeout(runtime, active, playbackTimeout, "AUDIO_READY");
            tryRecognizeDuringPlayback(runtime, active.opening());
        }));
    }

    @EventListener
    public void onTtsSegmentSent(AiRealtimeTtsSegmentSentEvent event) {
        if (event == null || StringUtils.isBlank(event.tenantId()) || StringUtils.isBlank(event.callId())) {
            return;
        }
        RuntimeSession runtime = sessions.get(key(event.tenantId(), event.callId()));
        if (runtime == null || runtime.closed.get()) {
            return;
        }
        ActiveSpeak active = runtime.activeSpeak.get();
        if (active == null || !matchesAudioReady(active, event.turnId(), event.sequence())) {
            return;
        }
        long timeout = resolvePlaybackCompletionTimeout(event.audioBytes(), event.sampleRate(),
            properties.getUnimrcp().getPlaybackCompleteTimeoutMs());
        scheduleSpeakTimeout(runtime, active, timeout, "AUDIO_SENT");
        log.info("AI UniMRCP 当前段 PCM 已全部发送，等待 FreeSWITCH 播放完成，sessionId={}，businessCallId={}，turnId={}，seq={}，generation={}，audioBytes={}，sampleRate={}，estimatedAudioMs={}，timeoutMs={}",
            runtime.entity.getId(), runtime.businessCallId, active.turnId(), active.seq(), active.generation(),
            event.audioBytes(), event.sampleRate(), pcmDurationMillis(event.audioBytes(), event.sampleRate()), timeout);
    }

    @EventListener
    public void onTtsFallbackStarted(AiRealtimeTtsFallbackStartedEvent event) {
        if (event == null || StringUtils.isBlank(event.tenantId()) || StringUtils.isBlank(event.callId())) {
            return;
        }
        RuntimeSession runtime = sessions.get(key(event.tenantId(), event.callId()));
        if (runtime == null || runtime.closed.get()) {
            return;
        }
        ActiveSpeak active = runtime.activeSpeak.get();
        if (active == null || !matchesAudioReady(active, event.turnId(), event.sequence())) {
            return;
        }
        runtime.httpFallbackPending.set(true);
        long fallbackTimeout = Math.max(30_000L,
            resolveSpeakCompletionTimeout(VoiceTransport.HTTP, estimateSpeakDelay(active.text()), null) + 15_000L);
        scheduleSpeakTimeout(runtime, active, fallbackTimeout, "HTTP_FALLBACK");
        log.warn("AI UniMRCP 已进入 HTTP 分句降级，延长首段音频等待窗口，sessionId={}，businessCallId={}，turnId={}，seq={}，timeoutMs={}，reason={}",
            runtime.entity.getId(), runtime.businessCallId, active.turnId(), active.seq(), fallbackTimeout, event.reason());
    }

    static boolean matchesAudioReady(ActiveSpeak active, String turnId, Integer sequence) {
        if (active == null || !StringUtils.equals(active.turnId(), turnId)) {
            return false;
        }
        return sequence == null || sequence <= 0 || active.seq() == sequence;
    }

    static boolean shouldFallbackBeforeCompleting(VoiceTransport transport, boolean audioReady) {
        return transport == VoiceTransport.WS && !audioReady;
    }

    private void recoverPrematureWsCompletion(RuntimeSession runtime, ActiveSpeak expected) {
        if (!runtime.activeSpeak.compareAndSet(expected, null)) {
            return;
        }
        runtime.audioReadySpeak.compareAndSet(expected, null);
        runtime.waitingSpeakComplete.set(false);
        ScheduledFuture<?> pending = runtime.pendingSpeakTimer.getAndSet(null);
        if (pending != null) {
            pending.cancel(false);
        }
        if (!runtime.httpFallbackActivated.compareAndSet(false, true)) {
            fail(runtime, "实时 TTS 未产生可播放音频且 HTTP 降级已执行，未启动下一轮识别", null);
            return;
        }
        int cancelledConnections = cancelSpeakConnections(runtime, expected);
        try {
            runtime.voiceTransport = VoiceTransport.HTTP;
            gateway().applyVoiceTransport(runtime.nodeId, runtime.customerLegUuid, VoiceTransport.HTTP, null);
            log.warn("AI UniMRCP 检测到 WS 播报在首段音频前提前结束，当前通话降级 HTTP 并重播，sessionId={}，businessCallId={}，turnId={}，seq={}，cancelledTtsConnections={}",
                runtime.entity.getId(), runtime.businessCallId, expected.turnId(), expected.seq(), cancelledConnections);
            speak(runtime, expected.text(), runtime.currentTurn.get(), expected.opening(), expected.control());
        } catch (Exception exception) {
            fail(runtime, "实时 TTS 提前结束后降级 HTTP 失败：" + exception.getMessage(), exception);
        }
    }

    private void recoverTimedOutSpeak(RuntimeSession runtime, ActiveSpeak expected, long timeoutMs, String phase) {
        if (!runtime.activeSpeak.compareAndSet(expected, null)) {
            return;
        }
        runtime.audioReadySpeak.compareAndSet(expected, null);
        runtime.waitingSpeakComplete.set(false);
        runtime.recognizing.set(false);
        runtime.pendingSpeakTimer.set(null);
        runtime.turnGeneration.compareAndSet(expected.generation(), expected.generation() + 1L);
        runtime.llmStreaming.set(false);
        runtime.workflowStreamingReply.set(false);
        runtime.postPlaybackAction.set(null);
        synchronized (runtime.pendingSpeakSegments) {
            runtime.pendingSpeakSegments.clear();
        }
        int cancelledConnections = cancelSpeakConnections(runtime, expected);
        try {
            gateway().stopPlayback(runtime.nodeId, runtime.customerLegUuid);
        } catch (Exception exception) {
            log.warn("AI UniMRCP 播报超时后停止残留播放失败，sessionId={}，businessCallId={}，error={}",
                runtime.entity.getId(), runtime.businessCallId, exception.getMessage());
        }
        AiRealtimeCallTurn timedOutTurn = runtime.currentTurn.getAndSet(null);
        markTurnPlaybackTimeout(timedOutTurn, phase, timeoutMs);
        runtime.turnInProgress.set(false);
        if (runtime.workflowActive.get() && runtime.workflowSpeak.compareAndSet(true, false)) {
            // Keep WAITING_TTS intact. The next user input resumes it through the interrupted path.
            resumeListening(runtime);
        } else if (expected.opening()) {
            listenAfterOpening(runtime);
        } else {
            resumeListening(runtime);
        }
        log.warn("AI UniMRCP 播报超时已恢复，sessionId={}，businessCallId={}，turnId={}，seq={}，generation={}，phase={}，timeoutMs={}，cancelledTtsConnections={}，nextState={}",
            runtime.entity.getId(), runtime.businessCallId, expected.turnId(), expected.seq(), expected.generation(),
            phase, timeoutMs, cancelledConnections, runtime.entity.getSessionState());
    }

    private void markTurnPlaybackTimeout(AiRealtimeCallTurn turn, String phase, long timeoutMs) {
        if (turn == null || "COMPLETED".equals(turn.getTurnState()) || "FAILED".equals(turn.getTurnState())) {
            return;
        }
        turn.setTurnState("FAILED");
        turn.setPlaybackEndedAt(LocalDateTime.now());
        turn.setFailureReason(limit("TTS 播放阶段超时并已恢复收音：phase=" + phase + ", timeoutMs=" + timeoutMs));
        turnMapper.updateById(turn);
    }

    private void completePreplayedOpeningOnPark(RuntimeSession runtime) {
        runtime.waitingSpeakComplete.set(false);
        ScheduledFuture<?> pending = runtime.pendingSpeakTimer.getAndSet(null);
        if (pending != null) {
            pending.cancel(false);
        }
        if (!runtime.conversationReady.get()) {
            log.info("AI UniMRCP 已进入 park，开场白播放结束，会话仍在初始化，sessionId={}，businessCallId={}，customerLegUuid={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid);
            return;
        }
        ActiveSpeak activeSpeak = runtime.activeSpeak.get();
        if (activeSpeak != null && activeSpeak.opening()) {
            completeSpeak(runtime, activeSpeak, "PARK");
            return;
        }
        listenAfterOpening(runtime);
        log.info("AI UniMRCP 已按 park 事件启动首轮识别，sessionId={}，businessCallId={}，customerLegUuid={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid);
    }

    private void completeSpeak(RuntimeSession runtime, ActiveSpeak expected, String source) {
        if (runtime.closed.get()) {
            return;
        }
        if (!runtime.activeSpeak.compareAndSet(expected, null)) {
            return;
        }
        runtime.audioReadySpeak.compareAndSet(expected, null);
        runtime.waitingSpeakComplete.set(false);
        ScheduledFuture<?> pendingTimer = runtime.pendingSpeakTimer.getAndSet(null);
        if (pendingTimer != null) {
            pendingTimer.cancel(false);
        }
        log.info("AI UniMRCP 播报段完成，sessionId={}，businessCallId={}，customerLegUuid={}，turnId={}，seq={}，source={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid,
            expected.turnId(), expected.seq(), source);
        if (expected.control()) {
            runtime.controlResumeInProgress.set(false);
        }
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
        if (runtime.workflowActive.get() && runtime.workflowSpeak.compareAndSet(true, false)) {
            try {
                runtime.workflowStreamingReply.set(false);
                handleWorkflowAction(runtime, workflowRuntimeService.voiceTtsCompleted(runtime.workflowExecutionId));
            } catch (Exception exception) {
                fail(runtime, "AI 语音工作流恢复失败：" + exception.getMessage(), exception);
            }
            return;
        }
        AiRealtimeCallTurn turn = runtime.currentTurn.getAndSet(null);
        if (turn != null) {
            finishTurn(runtime, turn);
        } else if (expected.opening()) {
            listenAfterOpening(runtime);
        } else {
            resumeListening(runtime);
        }
    }

    private void listenAfterOpening(RuntimeSession runtime) {
        markListening(runtime);
        if (processDeferredRecognition(runtime)) {
            return;
        }
        if (runtime.openingRecognitionActive.get()) {
            log.info("AI UniMRCP 复用开场白期间已启动的识别请求，等待客户首句话，sessionId={}，businessCallId={}，customerLegUuid={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid);
            return;
        }
        tryRecognize(runtime);
    }

    private void enqueueSpeak(RuntimeSession runtime, String sentence) {
        enqueueSpeak(runtime, sentence, runtime.turnGeneration.get());
    }

    private void enqueueSpeak(RuntimeSession runtime, String sentence, long generation) {
        if (runtime.closed.get() || generation != runtime.turnGeneration.get() || StringUtils.isBlank(sentence)) {
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
        // WS transport already streams each queued segment over the reusable provider session.
        // HTTP prewarming here would synthesize the same text twice and compete for provider capacity.
        if (runtime.voiceTransport == VoiceTransport.WS) {
            return;
        }
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
        String expectedText = StringUtils.trimToEmpty(activeText);
        String actualApplicationData = StringUtils.trimToEmpty(applicationData);
        if (StringUtils.isBlank(expectedText) || StringUtils.isBlank(actualApplicationData)) {
            return false;
        }
        return actualApplicationData.endsWith("|" + expectedText)
            || actualApplicationData.contains(expectedText);
    }

    static boolean shouldAcceptSpeakCompletion(ActiveSpeak active, String eventTurnId, Integer eventSeq,
                                               Long eventGeneration, String applicationData) {
        if (active == null) {
            return false;
        }
        boolean identified = false;
        if (StringUtils.isNotBlank(eventTurnId)) {
            identified = true;
            if (!StringUtils.equals(active.turnId(), eventTurnId)) {
                return false;
            }
        }
        if (eventSeq != null) {
            identified = true;
            if (active.seq() != eventSeq) {
                return false;
            }
        }
        if (eventGeneration != null) {
            identified = true;
            if (active.generation() != eventGeneration) {
                return false;
            }
        }
        if (identified) {
            return true;
        }
        if (StringUtils.isBlank(applicationData)) {
            return active.opening();
        }
        return matchesSpeakCompletion(active.text(), applicationData);
    }

    static String recognitionEventKey(Map<String, String> headers) {
        String sequence = firstHeader(headers, "Event-Sequence", "event-sequence");
        if (StringUtils.isNotBlank(sequence)) {
            return "sequence:" + sequence.trim();
        }
        String timestamp = firstHeader(headers, "Event-Date-Timestamp", "event-date-timestamp");
        if (StringUtils.isBlank(timestamp)) {
            return null;
        }
        String channelId = firstHeader(headers, "Channel-Identifier", "channel-identifier");
        return "timestamp:" + timestamp.trim() + ":channel:" + StringUtils.blankToDefault(channelId, "").trim();
    }

    private static String firstHeader(Map<String, String> headers, String... names) {
        if (headers == null) {
            return null;
        }
        for (String name : names) {
            String value = headers.get(name);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private void dispatchNextSegment(RuntimeSession runtime) {
        if (runtime.closed.get()) {
            return;
        }
        if (!ensureChannelAlive(runtime, "CHANNEL_GONE_BEFORE_NEXT_SEGMENT")) {
            return;
        }
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
        speak(runtime, next, turn, turn == null && !runtime.workflowStreamingReply.get());
    }

    private void finishTurn(RuntimeSession runtime, AiRealtimeCallTurn turn) {
        if (runtime.closed.get()) {
            cancelTurnAfterHangup(runtime, turn);
            return;
        }
        String state = turn.getTurnState();
        if ("INTERRUPTED".equals(state)) {
            runtime.turnInProgress.set(false);
            return;
        }
        if (!"COMPLETED".equals(state) && !"FAILED".equals(state)) {
            turn.setTurnState("COMPLETED");
            turn.setPlaybackEndedAt(LocalDateTime.now());
            turnMapper.updateById(turn);
        }
        log.info("AI UniMRCP 回合结束，sessionId={}，businessCallId={}，turn={}，totalMs={}",
            runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(),
            Duration.between(turn.getRecognizedAt(), LocalDateTime.now()).toMillis());
        runtime.turnInProgress.set(false);
        PendingIntentAction action = runtime.postPlaybackAction.getAndSet(null);
        if (action != null && executeIntentAction(runtime, action)) {
            return;
        }
        resumeListening(runtime);
    }

    private boolean executeIntentAction(RuntimeSession runtime, PendingIntentAction action) {
        if (runtime.closed.get()) {
            return true;
        }
        long startedNanos = System.nanoTime();
        try {
            if (!gateway().callExists(runtime.nodeId, runtime.customerLegUuid)) {
                end(runtime, "INTENT_ACTION_CHANNEL_GONE");
                return true;
            }
            switch (action.actionType()) {
                case "STOP_PLAYBACK" -> gateway().stopPlayback(runtime.nodeId, runtime.customerLegUuid);
                case "TRANSFER_EXTENSION" -> {
                    updateState(runtime, "TRANSFERRING", null);
                    notifyTicketTransfer(runtime);
                    gateway().transferToExtension(runtime.nodeId, runtime.customerLegUuid, action.target());
                    end(runtime, "INTENT_TRANSFER_EXTENSION");
                    return true;
                }
                case "TRANSFER_QUEUE" -> {
                    updateState(runtime, "TRANSFERRING", null);
                    notifyTicketTransfer(runtime);
                    gateway().transferToQueue(runtime.nodeId, runtime.customerLegUuid, action.target());
                    end(runtime, "INTENT_TRANSFER_QUEUE");
                    return true;
                }
                case "TRANSFER_IVR" -> {
                    updateState(runtime, "TRANSFERRING", null);
                    gateway().transferToIvr(runtime.tenantId, runtime.nodeId, runtime.customerLegUuid, action.target());
                    end(runtime, "INTENT_TRANSFER_IVR");
                    return true;
                }
                case "END_CALL" -> {
                    scheduleIntentHangup(runtime, action);
                    return true;
                }
                case "CREATE_TICKET" -> {
                    JsonNode config = JsonUtils.getObjectMapper().readTree(action.actionConfigJson());
                    Long templateId = config.path("templateId").asLong(0L);
                    if (templateId <= 0) throw new ServiceException("创建工单动作未配置工单模板");
                    AiIntentTicketActionService service = ticketActionServiceProvider.getIfAvailable();
                    if (service == null) throw new ServiceException("工单动作服务不可用");
                    Long ticketId = service.create(runtime.businessCallId, templateId,
                        config.path("submitAfterCreate").asBoolean(false));
                    log.info("AI intent created ticket, sessionId={}, businessCallId={}, intentCode={}, ticketId={}",
                        runtime.entity.getId(), runtime.businessCallId, action.intentCode(), ticketId);
                }
                default -> {
                    return false;
                }
            }
            log.info("AI intent action executed, sessionId={}, businessCallId={}, intentCode={}, actionType={}, target={}, costMs={}",
                runtime.entity.getId(), runtime.businessCallId, action.intentCode(), action.actionType(),
                action.target(), elapsedMillis(startedNanos));
            return false;
        } catch (Exception exception) {
            log.error("AI intent action execution failed, sessionId={}, businessCallId={}, intentCode={}, actionType={}, target={}, error={}",
                runtime.entity.getId(), runtime.businessCallId, action.intentCode(), action.actionType(),
                action.target(), exception.getMessage(), exception);
            updateState(runtime, "LISTENING", limit("意图动作执行失败：" + exception.getMessage()));
            return false;
        }
    }

    private void scheduleIntentHangup(RuntimeSession runtime, PendingIntentAction action) {
        updateState(runtime, "ENDING", null);
        long delayMs = Math.max(0L, properties.getUnimrcp().getIntentHangupDelayMs());
        ScheduledFuture<?> old = runtime.pendingActionTimer.getAndSet(null);
        if (old != null) {
            old.cancel(false);
        }
        ScheduledFuture<?> handle = scheduler.schedule(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (runtime.closed.get()) {
                return;
            }
            try {
                if (gateway().callExists(runtime.nodeId, runtime.customerLegUuid)) {
                    gateway().hangup(runtime.nodeId, runtime.customerLegUuid);
                }
                end(runtime, "INTENT_END_CALL");
                log.info("AI intent delayed hangup executed, sessionId={}, businessCallId={}, intentCode={}, delayMs={}",
                    runtime.entity.getId(), runtime.businessCallId, action.intentCode(), delayMs);
            } catch (Exception exception) {
                log.error("AI intent delayed hangup failed, sessionId={}, businessCallId={}, intentCode={}, error={}",
                    runtime.entity.getId(), runtime.businessCallId, action.intentCode(), exception.getMessage(), exception);
                end(runtime, "INTENT_END_CALL_FAILED");
            }
        }), Instant.now().plusMillis(delayMs));
        runtime.pendingActionTimer.set(handle);
        log.info("AI intent hangup scheduled after playback, sessionId={}, businessCallId={}, intentCode={}, delayMs={}",
            runtime.entity.getId(), runtime.businessCallId, action.intentCode(), delayMs);
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
            if (!gateway().callExists(runtime.nodeId, runtime.customerLegUuid)) {
                log.info("AI UniMRCP 提交识别前发现客户通道已不存在，结束会话，sessionId={}，businessCallId={}，customerLegUuid={}",
                    runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid);
                runtime.recognizing.set(false);
                end(runtime, "CHANNEL_GONE_BEFORE_RECOGNIZE");
                return;
            }
            gateway().recognize(runtime.nodeId, runtime.customerLegUuid, false, runtime.bargeInMode);
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
        markCallEnded(runtime.customerLegUuid);
        sessions.remove(key(runtime.tenantId, runtime.customerLegUuid));
        releaseOwnership(runtime.customerLegUuid);
        if (!runtime.closed.compareAndSet(false, true)) {
            return;
        }
        runtime.recognizing.set(false);
        runtime.openingRecognitionActive.set(false);
        runtime.turnGeneration.incrementAndGet();
        runtime.turnInProgress.set(false);
        runtime.llmStreaming.set(false);
        runtime.activeSpeak.set(null);
        runtime.waitingSpeakComplete.set(false);
        runtime.workflowSpeak.set(false);
        runtime.pendingConfirmation.set(null);
        runtime.postPlaybackAction.set(null);
        runtime.clearDeferredRecognition();
        synchronized (runtime.pendingSpeakSegments) {
            runtime.pendingSpeakSegments.clear();
        }
        ScheduledFuture<?> pendingSpeak = runtime.pendingSpeakTimer.getAndSet(null);
        if (pendingSpeak != null) {
            pendingSpeak.cancel(false);
        }
        ScheduledFuture<?> channelProbe = runtime.channelProbe.getAndSet(null);
        if (channelProbe != null) {
            channelProbe.cancel(false);
        }
        ScheduledFuture<?> pendingAction = runtime.pendingActionTimer.getAndSet(null);
        if (pendingAction != null) {
            pendingAction.cancel(false);
        }
        int cancelledTtsConnections = ttsConnectionRegistry.cancelByCallId(runtime.customerLegUuid);
        if (!StringUtils.equals(runtime.customerLegUuid, runtime.businessCallId)) {
            cancelledTtsConnections += ttsConnectionRegistry.cancelByCallId(runtime.businessCallId);
        }
        AiRealtimeCallTurn currentTurn = runtime.currentTurn.getAndSet(null);
        cancelTurnAfterHangup(runtime, currentTurn);
        runtime.entity.setSessionState("FAILED".equals(runtime.entity.getSessionState()) ? "FAILED" : "ENDED");
        runtime.entity.setEndedAt(LocalDateTime.now());
        runtime.entity.setLastActivityAt(LocalDateTime.now());
        sessionMapper.updateById(runtime.entity);
        finishRealtimeTranscript(runtime);
        if (StringUtils.isNotBlank(runtime.workflowExecutionId)) {
            try {
                workflowRuntimeService.terminate(runtime.workflowExecutionId, reason);
            } catch (Exception exception) {
                log.debug("AI 语音工作流终止状态更新失败，executionId={}，reason={}，error={}",
                    runtime.workflowExecutionId, reason, exception.getMessage());
            }
        }
        log.info("AI UniMRCP 会话结束，sessionId={}，businessCallId={}，customerLegUuid={}，reason={}，cancelledTtsConnections={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, reason, cancelledTtsConnections);
    }

    private void ensureChannelProbe(RuntimeSession runtime) {
        if (runtime.closed.get() || runtime.channelProbe.get() != null) {
            return;
        }
        long intervalMs = Math.max(500L, properties.getUnimrcp().getChannelProbeIntervalMs());
        ScheduledFuture<?> probe = scheduler.scheduleWithFixedDelay(
            () -> TenantHelper.dynamic(runtime.tenantId,
                () -> ensureChannelAlive(runtime, "CHANNEL_GONE_BY_PROBE")),
            Instant.now().plusMillis(intervalMs),
            Duration.ofMillis(intervalMs));
        if (!runtime.channelProbe.compareAndSet(null, probe)) {
            probe.cancel(false);
        }
    }

    private void tryRecognizeDuringPlayback(RuntimeSession runtime, boolean opening) {
        if (runtime.closed.get() || !shouldStartRecognitionDuringPlayback(opening, runtime.bargeInEnabled,
            runtime.openingBargeInEnabled, runtime.waitingSpeakComplete.get())) {
            return;
        }
        if (!runtime.recognizing.compareAndSet(false, true)) {
            return;
        }
        long recognizeNanos = System.nanoTime();
        try {
            if (!gateway().callExists(runtime.nodeId, runtime.customerLegUuid)) {
                runtime.recognizing.set(false);
                end(runtime, "CHANNEL_GONE_BEFORE_BARGE_IN_RECOGNIZE");
                return;
            }
            boolean interruptible = runtime.bargeInEnabled && (!opening || runtime.openingBargeInEnabled);
            gateway().recognize(runtime.nodeId, runtime.customerLegUuid, interruptible, runtime.bargeInMode);
            if (opening) {
                runtime.openingRecognitionActive.set(true);
            }
            log.info("AI UniMRCP 已在播报阶段启动识别，sessionId={}，businessCallId={}，customerLegUuid={}，opening={}，interruptible={}，mode={}，gatewayCostMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, opening,
                interruptible, runtime.bargeInMode, elapsedMillis(recognizeNanos));
        } catch (Exception exception) {
            if (opening) {
                runtime.openingRecognitionActive.set(false);
            }
            runtime.recognizing.set(false);
            log.warn("AI UniMRCP 播报阶段启动识别失败，本段继续播放，sessionId={}，businessCallId={}，error={}",
                runtime.entity.getId(), runtime.businessCallId, exception.getMessage());
        }
    }

    static boolean shouldStartRecognitionDuringPlayback(boolean opening, boolean bargeInEnabled,
                                                        boolean openingBargeInEnabled,
                                                        boolean waitingSpeakComplete) {
        return waitingSpeakComplete && bargeInEnabled && (!opening || openingBargeInEnabled);
    }

    private void handleBargeInBegin(RuntimeSession runtime) {
        ActiveSpeak active = runtime.activeSpeak.get();
        if (active == null || !bargeInAllowed(runtime, active)) {
            log.debug("AI UniMRCP 检测到用户开始说话，当前播报不允许打断，sessionId={}，businessCallId={}",
                runtime.entity.getId(), runtime.businessCallId);
            return;
        }
        long elapsedMs = (System.nanoTime() - active.startedNanos()) / 1_000_000L;
        if (elapsedMs < runtime.bargeInGraceMs) {
            log.info("AI UniMRCP 检测到用户开始说话但处于开播保护期，等待最终识别结果，sessionId={}，businessCallId={}，turnId={}，elapsedMs={}，graceMs={}",
                runtime.entity.getId(), runtime.businessCallId, active.turnId(), elapsedMs, runtime.bargeInGraceMs);
            return;
        }
        if (!handleControlBargeIn(runtime, "BEGIN_SPEAKING")) {
            interruptCurrentOutput(runtime, "BEGIN_SPEAKING");
        }
    }

    private boolean isBargeInPlayback(RuntimeSession runtime) {
        ActiveSpeak active = runtime.activeSpeak.get();
        return active != null && runtime.waitingSpeakComplete.get() && bargeInAllowed(runtime, active);
    }

    private boolean bargeInAllowed(RuntimeSession runtime, ActiveSpeak active) {
        return runtime.bargeInEnabled && !active.control() && (!active.opening() || runtime.openingBargeInEnabled);
    }

    private boolean handleControlBargeIn(RuntimeSession runtime, String reason) {
        if (!"BEGIN_SPEAKING".equals(reason)
            || !"CONTROL_RESUME".equals(runtime.bargeInStrategy) || runtime.closed.get()) {
            return false;
        }
        ActiveSpeak active = runtime.activeSpeak.get();
        if (runtime.controlResumeInProgress.get()) {
            return true;
        }
        if (active == null || !bargeInAllowed(runtime, active)) {
            return false;
        }
        long now = System.nanoTime();
        long cooldownNanos = runtime.bargeInControlCooldownMs * 1_000_000L;
        long lastControl = runtime.lastControlBargeInNanos.get();
        if (!controlBargeInAvailable(runtime.controlBargeInCount.get(), runtime.bargeInControlMaxCount,
            now, lastControl, cooldownNanos)) {
            log.info("AI UniMRCP 控场次数或冷却限制生效，本次降级为普通打断，sessionId={}，businessCallId={}，reason={}，count={}，maxCount={}，cooldownMs={}",
                runtime.entity.getId(), runtime.businessCallId, reason, runtime.controlBargeInCount.get(),
                runtime.bargeInControlMaxCount, runtime.bargeInControlCooldownMs);
            return false;
        }
        if (!runtime.interrupting.compareAndSet(false, true)) {
            return true;
        }
        try {
            if (!runtime.activeSpeak.compareAndSet(active, null)
                || !runtime.controlResumeInProgress.compareAndSet(false, true)) {
                return true;
            }
            runtime.controlBargeInCount.incrementAndGet();
            runtime.lastControlBargeInNanos.set(now);
            runtime.waitingSpeakComplete.set(false);
            ScheduledFuture<?> pending = runtime.pendingSpeakTimer.getAndSet(null);
            if (pending != null) {
                pending.cancel(false);
            }
            int cancelledTtsConnections = cancelSpeakConnections(runtime, active);
            try {
                gateway().stopPlayback(runtime.nodeId, runtime.customerLegUuid);
            } catch (Exception exception) {
                log.warn("AI UniMRCP 控场时停止 FreeSWITCH 播放失败，继续插入控场话术，sessionId={}，businessCallId={}，error={}",
                    runtime.entity.getId(), runtime.businessCallId, exception.getMessage());
            }
            synchronized (runtime.pendingSpeakSegments) {
                requeueInterruptedSegment(runtime.pendingSpeakSegments, active.text());
            }
            String controlText = resolveControlText(runtime);
            appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_AI, SOURCE_AI_GENERATED,
                controlText, LocalDateTime.now(), runtime.agentId);
            try {
                speak(runtime, controlText, runtime.currentTurn.get(), false, true);
            } catch (Exception exception) {
                runtime.controlResumeInProgress.set(false);
                runtime.waitingSpeakComplete.set(false);
                runtime.activeSpeak.set(null);
                log.warn("AI UniMRCP 控场话术播放失败，直接恢复原播报，sessionId={}，businessCallId={}，error={}",
                    runtime.entity.getId(), runtime.businessCallId, exception.getMessage());
                dispatchNextSegment(runtime);
            }
            log.info("AI UniMRCP 已插入控场话术并保留原播报队列，sessionId={}，businessCallId={}，turnId={}，seq={}，reason={}，count={}，cancelledTtsConnections={}",
                runtime.entity.getId(), runtime.businessCallId, active.turnId(), active.seq(), reason,
                runtime.controlBargeInCount.get(), cancelledTtsConnections);
            return true;
        } finally {
            runtime.interrupting.set(false);
        }
    }

    private String resolveControlText(RuntimeSession runtime) {
        String resolved = workflowTemplateResolver.resolve(runtime.bargeInControlText, runtime.templateVariables).trim();
        return StringUtils.defaultIfBlank(resolved, "您先别急，请让我把这段内容说明完，很快就好。");
    }

    private int cancelSpeakConnections(RuntimeSession runtime, ActiveSpeak active) {
        int cancelled = ttsConnectionRegistry.cancelByCallIdAndTurnId(runtime.customerLegUuid, active.turnId());
        if (!StringUtils.equals(runtime.customerLegUuid, runtime.businessCallId)) {
            cancelled += ttsConnectionRegistry.cancelByCallIdAndTurnId(runtime.businessCallId, active.turnId());
        }
        return cancelled;
    }

    static boolean controlBargeInAvailable(int count, int maxCount, long nowNanos,
                                           long lastControlNanos, long cooldownNanos) {
        return count < maxCount
            && (lastControlNanos <= 0L || nowNanos - lastControlNanos >= cooldownNanos);
    }

    static void requeueInterruptedSegment(Deque<String> pendingSegments, String interruptedText) {
        pendingSegments.offerFirst(interruptedText);
    }

    private void interruptCurrentOutput(RuntimeSession runtime, String reason) {
        if (runtime.closed.get() || !runtime.interrupting.compareAndSet(false, true)) {
            return;
        }
        try {
            ActiveSpeak active = runtime.activeSpeak.getAndSet(null);
            if (active == null || !bargeInAllowed(runtime, active)) {
                return;
            }
            runtime.turnGeneration.incrementAndGet();
            runtime.waitingSpeakComplete.set(false);
            runtime.workflowSpeak.set(false);
            runtime.workflowStreamingReply.set(false);
            runtime.llmStreaming.set(false);
            runtime.postPlaybackAction.set(null);
            synchronized (runtime.pendingSpeakSegments) {
                runtime.pendingSpeakSegments.clear();
            }
            ScheduledFuture<?> pending = runtime.pendingSpeakTimer.getAndSet(null);
            if (pending != null) {
                pending.cancel(false);
            }
            int cancelledTtsConnections = cancelSpeakConnections(runtime, active);
            try {
                gateway().stopPlayback(runtime.nodeId, runtime.customerLegUuid);
            } catch (Exception exception) {
                log.warn("AI UniMRCP 打断时停止 FreeSWITCH 播放失败，sessionId={}，businessCallId={}，error={}",
                    runtime.entity.getId(), runtime.businessCallId, exception.getMessage());
            }
            AiRealtimeCallTurn interruptedTurn = runtime.currentTurn.getAndSet(null);
            markTurnInterrupted(interruptedTurn, reason);
            runtime.turnInProgress.set(false);
            updateState(runtime, "LISTENING", null);
            log.info("AI UniMRCP 播报已被用户打断，sessionId={}，businessCallId={}，turnId={}，seq={}，reason={}，cancelledTtsConnections={}",
                runtime.entity.getId(), runtime.businessCallId, active.turnId(), active.seq(), reason,
                cancelledTtsConnections);
        } finally {
            runtime.interrupting.set(false);
        }
    }

    private void markTurnInterrupted(AiRealtimeCallTurn turn, String reason) {
        if (turn == null || "INTERRUPTED".equals(turn.getTurnState()) || "COMPLETED".equals(turn.getTurnState())) {
            return;
        }
        turn.setTurnState("INTERRUPTED");
        turn.setPlaybackEndedAt(LocalDateTime.now());
        turn.setFailureReason(limit("用户打断：" + reason));
        turnMapper.updateById(turn);
    }

    private boolean ensureChannelAlive(RuntimeSession runtime, String endReason) {
        if (runtime.closed.get()) {
            return false;
        }
        try {
            if (gateway().callExists(runtime.nodeId, runtime.customerLegUuid)) {
                return true;
            }
            log.info("AI UniMRCP 存活检查发现客户通道已不存在，结束会话，sessionId={}，businessCallId={}，customerLegUuid={}，reason={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, endReason);
            end(runtime, endReason);
            return false;
        } catch (Exception exception) {
            log.warn("AI UniMRCP 存活检查失败，本次不推进会话，sessionId={}，businessCallId={}，customerLegUuid={}，reason={}，error={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, endReason, exception.getMessage());
            return false;
        }
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
                transcriptStreamService.publishSegment(runtime.tenantId, source.getId(), transcript.getId(), segment);
                ticketDraftTriggerService.onTranscriptSegment(runtime.tenantId, runtime.businessCallId, transcript.getId());
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

    static long resolveRealtimeIntentTimeout(Long configuredTimeoutMs) {
        long configured = configuredTimeoutMs == null ? 1800L : configuredTimeoutMs;
        return Math.max(500L, Math.min(configured, 2000L));
    }

    private void notifyTicketTransfer(RuntimeSession runtime) {
        try {
            AiCallTranscript transcript = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
                .eq(AiCallTranscript::getBusinessCallId, runtime.businessCallId)
                .orderByDesc(AiCallTranscript::getId).last("LIMIT 1"));
            ticketDraftTriggerService.onTransferToAgent(runtime.tenantId, runtime.businessCallId,
                transcript == null ? null : transcript.getId());
        } catch (Exception exception) {
            log.warn("AI 转人工工单草稿刷新触发失败，不阻塞转接，businessCallId={}，error={}",
                runtime.businessCallId, exception.getMessage());
        }
    }

    private void processWorkflowInput(RuntimeSession runtime, String text) {
        if (runtime.closed.get() || StringUtils.isBlank(runtime.workflowExecutionId)) {
            return;
        }
        try {
            AiWorkflowVoiceExecutionResponse result = workflowRuntimeService.voiceInput(
                runtime.workflowExecutionId, UUID.randomUUID().toString(), text);
            handleWorkflowAction(runtime, result);
        } catch (Exception exception) {
            runtime.turnInProgress.set(false);
            fail(runtime, "AI 语音工作流处理输入失败：" + exception.getMessage(), exception);
        }
    }

    private void handleWorkflowAction(RuntimeSession runtime, AiWorkflowVoiceExecutionResponse result) {
        if (runtime.closed.get()) return;
        String actionType = StringUtils.blankToDefault(result.getActionType(), result.getStatus());
        switch (actionType) {
            case "STREAM_AI" -> streamWorkflowReply(runtime, result);
            case "SPEAK" -> {
                if (StringUtils.isBlank(result.getText())) {
                    handleWorkflowAction(runtime, workflowRuntimeService.voiceTtsCompleted(result.getExecutionId()));
                    return;
                }
                runtime.workflowSpeak.set(true);
                runtime.lastAssistantText = result.getText();
                appendRealtimeTranscriptSegment(runtime, SPEAKER_AI, SOURCE_AI_GENERATED,
                    result.getText(), LocalDateTime.now(), runtime.agentId);
                boolean opening = runtime.workflowOpeningPending.compareAndSet(true, false);
                speak(runtime, result.getText(), null, opening);
            }
            case "PLAY_MEDIA" -> {
                runtime.workflowOpeningPending.set(false);
                playWorkflowMedia(runtime, result);
            }
            case "WAIT_INPUT" -> {
                runtime.workflowOpeningPending.set(false);
                runtime.workflowSpeak.set(false);
                resumeListening(runtime);
            }
            case "TRANSFER_QUEUE", "TRANSFER_EXTENSION", "TRANSFER_IVR" ->
                executeWorkflowTransfer(runtime, actionType, result.getTarget());
            case "HANGUP" -> {
                gateway().hangup(runtime.nodeId, runtime.customerLegUuid);
                end(runtime, "WORKFLOW_" + actionType);
            }
            case "COMPLETED" -> continueWithDefaultConversation(runtime);
            case "FAILED" -> fail(runtime, "AI 语音工作流执行失败：" + result.getFailureMessage(), null);
            default -> throw new ServiceException("AI 语音工作流返回了无法执行的动作：" + actionType);
        }
    }

    private void playWorkflowMedia(RuntimeSession runtime, AiWorkflowVoiceExecutionResponse action) {
        Long mediaId = parseLong(action.getTarget());
        if (mediaId == null) {
            throw new ServiceException("播放声音媒体节点未选择有效媒体");
        }
        AiGeneratedMediaQueryService.SyncedMedia media = generatedMediaQueryService.findSyncedMedia(mediaId, runtime.nodeId);
        if (media == null || StringUtils.isBlank(media.path())) {
            throw new ServiceException("声音媒体未发布到当前 FreeSWITCH 节点或同步尚未成功");
        }
        ScheduledFuture<?> old = runtime.pendingSpeakTimer.getAndSet(null);
        if (old != null) old.cancel(false);
        runtime.workflowMediaPlayback.set(true);
        try {
            gateway().playMedia(runtime.nodeId, runtime.customerLegUuid, media.path());
        } catch (Exception exception) {
            runtime.workflowMediaPlayback.set(false);
            throw exception;
        }
        long timeoutMs = media.durationMs() == null
            ? 120_000L
            : Math.min(120_000L, media.durationMs() + 120L);
        ScheduledFuture<?> timeout = scheduler.schedule(
            () -> TenantHelper.dynamic(runtime.tenantId, () -> completeWorkflowMedia(runtime, "TIMEOUT")),
            Instant.now().plusMillis(timeoutMs));
        runtime.pendingSpeakTimer.set(timeout);
        if (!runtime.workflowMediaPlayback.get() && runtime.pendingSpeakTimer.compareAndSet(timeout, null)) {
            timeout.cancel(false);
        }
        log.info("AI workflow media playback started, sessionId={}, businessCallId={}, mediaId={}, assetName={}, path={}, timeoutMs={}",
            runtime.entity.getId(), runtime.businessCallId, mediaId, media.assetName(), media.path(), timeoutMs);
    }

    private void completeWorkflowMedia(RuntimeSession runtime, String source) {
        if (runtime.closed.get() || !runtime.workflowMediaPlayback.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> pending = runtime.pendingSpeakTimer.getAndSet(null);
        if (pending != null) pending.cancel(false);
        log.info("AI workflow media playback completed, sessionId={}, businessCallId={}, source={}",
            runtime.entity.getId(), runtime.businessCallId, source);
        try {
            handleWorkflowAction(runtime, workflowRuntimeService.voiceTtsCompleted(runtime.workflowExecutionId));
        } catch (Exception exception) {
            fail(runtime, "播放声音媒体后恢复工作流失败：" + exception.getMessage(), exception);
        }
    }

    private void streamWorkflowReply(RuntimeSession runtime, AiWorkflowVoiceExecutionResponse action) {
        String prompt = action.getText();
        if (StringUtils.isBlank(prompt)) {
            fail(runtime, "AI 语音工作流流式回答内容为空", null);
            return;
        }
        long generation = runtime.turnGeneration.get();
        runtime.workflowSpeak.set(true);
        runtime.workflowStreamingReply.set(true);
        runtime.workflowStreamTurnId = "workflow-" + runtime.workflowStreamSequence.incrementAndGet();
        runtime.segmenter = new SentenceSegmenter();
        synchronized (runtime.pendingSpeakSegments) {
            runtime.pendingSpeakSegments.clear();
        }
        runtime.speakSegmentSeq.set(0);
        runtime.llmStreaming.set(true);
        updateState(runtime, "THINKING", null);

        AiChatRequest request = new AiChatRequest();
        request.setConversationId(runtime.entity.getConversationId());
        request.setMessage(prompt);
        StringBuilder fullAnswer = new StringBuilder();
        AtomicReference<Long> conversationId = new AtomicReference<>(runtime.entity.getConversationId());
        AtomicReference<String> sourceType = new AtomicReference<>("MODEL");
        AtomicReference<Map<String, Object>> retrieval = new AtomicReference<>(Map.of());
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicLong firstDeltaNanos = new AtomicLong();
        AtomicLong firstSpeakReadyNanos = new AtomicLong();
        long startedNanos = System.nanoTime();

        BiConsumer<String, Object> consumer = (event, data) -> {
            if (runtime.closed.get() || generation != runtime.turnGeneration.get() || !(data instanceof Map<?, ?> values)) {
                return;
            }
            switch (event) {
                case "conversation" -> {
                    if (values.get("conversationId") != null) {
                        conversationId.set(Long.valueOf(String.valueOf(values.get("conversationId"))));
                    }
                }
                case "retrieval" -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    values.forEach((key, value) -> summary.put(String.valueOf(key), value));
                    retrieval.set(summary);
                }
                case "delta" -> {
                    Object content = values.get("content");
                    if (content == null) return;
                    String piece = String.valueOf(content);
                    firstDeltaNanos.compareAndSet(0L, System.nanoTime());
                    fullAnswer.append(piece);
                    List<String> sentences = runtime.segmenter.append(piece);
                    if (!sentences.isEmpty() && firstSpeakReadyNanos.compareAndSet(0L, System.nanoTime())) {
                        log.info("AI 工作流首个可播句已生成，sessionId={}，businessCallId={}，firstDeltaMs={}，firstSpeakReadyMs={}，segmentLength={}",
                            runtime.entity.getId(), runtime.businessCallId,
                            (firstDeltaNanos.get() - startedNanos) / 1_000_000L,
                            (firstSpeakReadyNanos.get() - startedNanos) / 1_000_000L,
                            sentences.get(0).length());
                    }
                    for (String sentence : sentences) {
                        enqueueSpeak(runtime, sentence, generation);
                        appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_AI, SOURCE_AI_GENERATED,
                            sentence, LocalDateTime.now(), runtime.agentId);
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
            if ("MODEL_REPLY".equals(action.getTarget())) {
                agentService.streamChatModel(runtime.agentId, 0L, request, consumer);
            } else {
                agentService.streamChat(runtime.agentId, 0L, request, consumer);
            }
            if (runtime.closed.get() || generation != runtime.turnGeneration.get()) {
                return;
            }
            if (StringUtils.isNotBlank(failure.get())) {
                throw new ServiceException(failure.get());
            }
            String tail = runtime.segmenter.drain();
            if (StringUtils.isNotBlank(tail)) {
                enqueueSpeak(runtime, tail, generation);
                appendRealtimeTranscriptSegmentAsync(runtime, SPEAKER_AI, SOURCE_AI_GENERATED,
                    tail, LocalDateTime.now(), runtime.agentId);
            }
            if (fullAnswer.isEmpty()) {
                throw new ServiceException("AI 助手未返回可播报内容");
            }
            AiChatTurnResult turn = new AiChatTurnResult(conversationId.get(), fullAnswer.toString(),
                sourceType.get(), retrieval.get());
            workflowRuntimeService.voiceStreamCompleted(action.getExecutionId(), turn);
            runtime.entity.setConversationId(conversationId.get());
            sessionMapper.updateById(runtime.entity);
            runtime.lastAssistantText = fullAnswer.toString();
            runtime.llmStreaming.set(false);
            prewarmPendingSegments(runtime);
            log.info("AI 工作流流式回答生成完成，sessionId={}，businessCallId={}，sourceType={}，answerLength={}，firstDeltaMs={}，firstSpeakReadyMs={}，totalMs={}",
                runtime.entity.getId(), runtime.businessCallId, sourceType.get(), fullAnswer.length(),
                firstDeltaNanos.get() == 0L ? null : (firstDeltaNanos.get() - startedNanos) / 1_000_000L,
                firstSpeakReadyNanos.get() == 0L ? null : (firstSpeakReadyNanos.get() - startedNanos) / 1_000_000L,
                elapsedMillis(startedNanos));
            completeWorkflowStreamIfIdle(runtime);
        } catch (Exception exception) {
            runtime.llmStreaming.set(false);
            runtime.workflowStreamingReply.set(false);
            runtime.workflowSpeak.set(false);
            fail(runtime, "AI 语音工作流流式回答失败：" + exception.getMessage(), exception);
        }
    }

    private void completeWorkflowStreamIfIdle(RuntimeSession runtime) {
        boolean pending;
        synchronized (runtime.pendingSpeakSegments) {
            pending = !runtime.pendingSpeakSegments.isEmpty();
        }
        if (pending || runtime.activeSpeak.get() != null || runtime.waitingSpeakComplete.get()) {
            return;
        }
        if (runtime.workflowSpeak.compareAndSet(true, false)) {
            runtime.workflowStreamingReply.set(false);
            handleWorkflowAction(runtime, workflowRuntimeService.voiceTtsCompleted(runtime.workflowExecutionId));
        }
    }

    private void continueWithDefaultConversation(RuntimeSession runtime) {
        AiConversationStartResponse start = agentService.startRealtimeConversation(runtime.agentId);
        runtime.entity.setConversationId(Long.valueOf(String.valueOf(start.getConversation().getId())));
        sessionMapper.updateById(runtime.entity);
        runtime.workflowActive.set(false);
        runtime.workflowSpeak.set(false);
        runtime.workflowStreamingReply.set(false);
        resumeListening(runtime);
        log.info("AI 语音工作流正常结束，已交回默认 AI 对话，sessionId={}，businessCallId={}，executionId={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.workflowExecutionId);
    }

    private void executeWorkflowTransfer(RuntimeSession runtime, String actionType, String target) {
        if (StringUtils.isBlank(target)) throw new ServiceException("AI 语音工作流转接目标为空：" + actionType);
        updateState(runtime, "TRANSFERRING", null);
        switch (actionType) {
            case "TRANSFER_QUEUE" -> {
                notifyTicketTransfer(runtime);
                gateway().transferToQueue(runtime.nodeId, runtime.customerLegUuid, target);
            }
            case "TRANSFER_EXTENSION" -> {
                notifyTicketTransfer(runtime);
                gateway().transferToExtension(runtime.nodeId, runtime.customerLegUuid, target);
            }
            case "TRANSFER_IVR" -> gateway().transferToIvr(runtime.tenantId, runtime.nodeId, runtime.customerLegUuid, target);
            default -> throw new ServiceException("不支持的工作流转接动作：" + actionType);
        }
        end(runtime, "WORKFLOW_" + actionType);
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
                ticketDraftTriggerService.onTranscriptReady(runtime.tenantId, runtime.businessCallId, transcript.getId());
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
        if (runtime.closed.get() && !"ENDED".equals(state) && !"FAILED".equals(state)) {
            return;
        }
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

    private List<RuntimeSession> findTerminalSessions(Long nodeId, String uuid, Map<String, String> headers) {
        List<RuntimeSession> directMatches = sessions.values().stream()
            .filter(runtime -> nodeId == null || nodeId.equals(runtime.nodeId))
            .filter(runtime -> StringUtils.equals(uuid, runtime.customerLegUuid)
                || StringUtils.equals(uuid, runtime.businessCallId))
            .distinct()
            .toList();
        if (!directMatches.isEmpty()) {
            return directMatches;
        }
        Set<String> relatedIds = new LinkedHashSet<>();
        addRelatedId(relatedIds, header(headers, VAR_CUSTOMER_LEG_UUID));
        addRelatedId(relatedIds, header(headers, VAR_BUSINESS_CALL_ID));
        addRelatedId(relatedIds, header(headers, "Channel-Call-UUID"));
        addRelatedId(relatedIds, header(headers, "Other-Leg-Unique-ID"));
        addRelatedId(relatedIds, header(headers, "Bridge-A-Unique-ID"));
        addRelatedId(relatedIds, header(headers, "Bridge-B-Unique-ID"));
        addRelatedId(relatedIds, header(headers, "bridge_uuid"));
        addRelatedId(relatedIds, header(headers, "origination_uuid"));
        addRelatedId(relatedIds, header(headers, "cc_member_uuid"));
        if (headers != null) {
            headers.forEach((name, value) -> {
                String normalizedName = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (normalizedName.contains("uuid") || normalizedName.contains("business_call_id")) {
                    addRelatedId(relatedIds, value);
                }
            });
        }
        if (relatedIds.isEmpty()) {
            return List.of();
        }
        return sessions.values().stream()
            .filter(runtime -> nodeId == null || nodeId.equals(runtime.nodeId))
            .filter(runtime -> relatedIds.contains(runtime.customerLegUuid)
                || relatedIds.contains(runtime.businessCallId))
            // Related UUIDs are also inherited by short-lived MRCP media channels. Only close an
            // indirectly matched runtime after the actual customer channel has disappeared.
            .filter(runtime -> !customerChannelExists(runtime))
            .distinct()
            .toList();
    }

    private boolean customerChannelExists(RuntimeSession runtime) {
        try {
            return gateway().callExists(runtime.nodeId, runtime.customerLegUuid);
        } catch (Exception exception) {
            log.warn("AI UniMRCP 终止事件校验客户通道失败，暂不关闭间接匹配会话，sessionId={}，customerLegUuid={}，error={}",
                runtime.entity.getId(), runtime.customerLegUuid, exception.getMessage());
            return true;
        }
    }

    private void addRelatedId(Set<String> relatedIds, String value) {
        if (StringUtils.isNotBlank(value)) {
            relatedIds.add(value.trim());
        }
    }

    private void cancelTurnAfterHangup(RuntimeSession runtime, AiRealtimeCallTurn turn) {
        if (turn == null || "COMPLETED".equals(turn.getTurnState())
            || "FAILED".equals(turn.getTurnState()) || "CANCELLED".equals(turn.getTurnState())) {
            return;
        }
        turn.setTurnState("CANCELLED");
        turn.setFailureReason("通话已挂断，取消未完成的 AI 回合");
        turn.setPlaybackEndedAt(LocalDateTime.now());
        turnMapper.updateById(turn);
    }

    private boolean isTerminal(String eventName) {
        return "CHANNEL_HANGUP".equals(eventName)
            || "CHANNEL_HANGUP_COMPLETE".equals(eventName)
            || "CHANNEL_DESTROY".equals(eventName);
    }

    private boolean isSpeakComplete(String eventName, String application) {
        return "CHANNEL_EXECUTE_COMPLETE".equals(eventName) && "speak".equalsIgnoreCase(application);
    }

    static boolean isOpeningParkEntered(String eventName, String application) {
        return "CHANNEL_EXECUTE".equals(eventName) && "park".equalsIgnoreCase(application);
    }

    private boolean isMediaPlaybackComplete(String eventName, String application) {
        return "CHANNEL_EXECUTE_COMPLETE".equals(eventName) && "playback".equalsIgnoreCase(application);
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

    private Integer parseInteger(String value) {
        try {
            return StringUtils.isBlank(value) ? null : Integer.valueOf(value);
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

    static long estimateSpeakDelay(String text) {
        if (StringUtils.isBlank(text)) {
            return 1200L;
        }
        long spokenUnits = text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
        long pauseMarks = text.codePoints()
            .filter(codePoint -> "，。！？；：、（）,.!?;:".indexOf(codePoint) >= 0)
            .count();
        // This is only the fallback used when the real playback-complete event is lost.
        // Keep it conservative so long fixed templates do not reopen ASR while audio is still playing.
        return Math.max(1200L, spokenUnits * 220L + pauseMarks * 250L + 1200L);
    }

    private long speakCompletionTimeout(RuntimeSession runtime, String text) {
        long estimatedDelay = estimateSpeakDelay(text) + properties.getUnimrcp().getSpeakCompleteDelayMs();
        return resolveSpeakCompletionTimeout(runtime.voiceTransport, estimatedDelay,
            properties.getUnimrcp().getStreamingSpeakCompleteTimeoutMs());
    }

    private long audioReadyCompletionTimeout(RuntimeSession runtime, String text) {
        long configured = properties.getUnimrcp().getStreamingAudioSendTimeoutMs() == null
            ? 120000L : properties.getUnimrcp().getStreamingAudioSendTimeoutMs();
        return Math.max(speakCompletionTimeout(runtime, text), Math.max(30000L, Math.min(configured, 300000L)));
    }

    static long resolvePlaybackCompletionTimeout(long audioBytes, int sampleRate, Long configuredTimeoutMs) {
        long duration = pcmDurationMillis(audioBytes, sampleRate);
        long configured = configuredTimeoutMs == null ? 30000L : configuredTimeoutMs;
        long boundedMinimum = Math.max(15000L, Math.min(configured, 300000L));
        long durationWithMargin = duration + Math.max(10000L, duration / 2L);
        return Math.max(boundedMinimum, durationWithMargin);
    }

    static long pcmDurationMillis(long audioBytes, int sampleRate) {
        int normalizedRate = sampleRate > 0 ? sampleRate : 8000;
        long bytesPerSecond = normalizedRate * 2L;
        return audioBytes <= 0 ? 0L : (audioBytes * 1000L + bytesPerSecond - 1L) / bytesPerSecond;
    }

    static long resolveSpeakCompletionTimeout(VoiceTransport transport, long estimatedDelay,
                                               Long streamingTimeoutMs) {
        long normalizedEstimate = Math.max(1200L, estimatedDelay);
        long estimateWithMargin = Math.max(normalizedEstimate + 5000L,
            (normalizedEstimate * 3L + 1L) / 2L);
        if (transport != VoiceTransport.WS) {
            return estimateWithMargin;
        }
        long configured = streamingTimeoutMs == null ? 12000L : streamingTimeoutMs;
        // 配置只控制短文本的最短等待窗口。旧环境即使仍覆盖为 180000，
        // 也不能让实时通话因完成事件丢失而固定阻塞数分钟。
        // 首包迟迟不到时，短文本会在 12 到 15 秒内主动切换 HTTP 重播；收到首包后，
        // 同一定时器改为只保护实际播放阶段，避免在回退音频尚未播放完成时提前打开 ASR。
        long safeTimeout = Math.max(12000L, Math.min(configured, 15000L));
        return Math.max(estimateWithMargin, safeTimeout);
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

    static record ActiveSpeak(String turnId, int seq, String text, long startedNanos, long generation,
                              boolean opening, boolean control) {
    }

    private record PendingIntentAction(String intentCode, String intentName, String actionType,
                                       String target, String responseTemplate, String actionConfigJson) {
    }

    private static final class RuntimeSession {
        private final String tenantId;
        private final Long nodeId;
        private final Long agentId;
        private final String businessCallId;
        private final String customerLegUuid;
        private final String agentName;
        private final String ttsVoice;
        private final AiRealtimeCallSession entity;
        private final boolean openingPreplayed;
        private volatile VoiceTransport voiceTransport;
        private final String voiceTransportWsUrl;
        private final boolean bargeInEnabled;
        private final boolean openingBargeInEnabled;
        private final String bargeInMode;
        private final int bargeInGraceMs;
        private final String bargeInStrategy;
        private final String bargeInControlText;
        private final int bargeInControlMaxCount;
        private final int bargeInControlCooldownMs;
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean conversationReady = new AtomicBoolean();
        private final AtomicBoolean preplayedOpeningCompleted = new AtomicBoolean();
        private final AtomicBoolean turnInProgress = new AtomicBoolean();
        private final AtomicBoolean waitingSpeakComplete = new AtomicBoolean();
        private final AtomicBoolean recognizing = new AtomicBoolean();
        private final AtomicBoolean openingRecognitionActive = new AtomicBoolean();
        private final AtomicBoolean interrupting = new AtomicBoolean();
        private final AtomicBoolean controlResumeInProgress = new AtomicBoolean();
        private final AtomicInteger controlBargeInCount = new AtomicInteger();
        private final AtomicLong lastControlBargeInNanos = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong turnGeneration = new AtomicLong();
        private final AtomicInteger transcriptSentenceIndex = new AtomicInteger();
        private final AtomicInteger consecutiveEmptyRecognitions = new AtomicInteger();
        private final Object transcriptPersistenceLock = new Object();
        final Deque<String> pendingSpeakSegments = new ArrayDeque<>();
        final AtomicReference<AiRealtimeCallTurn> currentTurn = new AtomicReference<>();
        final AtomicReference<ActiveSpeak> activeSpeak = new AtomicReference<>();
        final AtomicReference<ActiveSpeak> audioReadySpeak = new AtomicReference<>();
        final AtomicBoolean httpFallbackActivated = new AtomicBoolean();
        final AtomicBoolean httpFallbackPending = new AtomicBoolean();
        final AtomicReference<PendingIntentAction> pendingConfirmation = new AtomicReference<>();
        final AtomicReference<PendingIntentAction> postPlaybackAction = new AtomicReference<>();
        final DeferredRecognitionSlot deferredRecognition = new DeferredRecognitionSlot();
        final AtomicBoolean llmStreaming = new AtomicBoolean();
        final AtomicBoolean workflowActive = new AtomicBoolean();
        final AtomicBoolean workflowOpeningPending = new AtomicBoolean(true);
        final AtomicBoolean workflowSpeak = new AtomicBoolean();
        final AtomicBoolean workflowMediaPlayback = new AtomicBoolean();
        final AtomicBoolean workflowStreamingReply = new AtomicBoolean();
        final AtomicInteger workflowStreamSequence = new AtomicInteger();
        final AtomicReference<ScheduledFuture<?>> pendingSpeakTimer = new AtomicReference<>();
        final AtomicReference<ScheduledFuture<?>> channelProbe = new AtomicReference<>();
        final AtomicReference<ScheduledFuture<?>> pendingActionTimer = new AtomicReference<>();
        /** 当前轮内的 TTS 段序号，随 speak 递增；每轮开始由 processTurn 重置。 */
        final AtomicInteger speakSegmentSeq = new AtomicInteger();
        volatile SentenceSegmenter segmenter;
        private final Set<String> recentRecognitionEventIds = new LinkedHashSet<>();
        private volatile String lastAssistantText;
        private volatile String workflowExecutionId;
        private volatile String workflowStreamTurnId;
        private volatile Map<String, Object> templateVariables = Map.of();
        private volatile LocalDateTime lastActivityAt;

        private RuntimeSession(String tenantId, Long nodeId, Long agentId, String businessCallId,
                               String customerLegUuid, String agentName, String ttsVoice,
                               AiRealtimeCallSession entity, boolean openingPreplayed,
                               VoiceTransport voiceTransport, String voiceTransportWsUrl, boolean bargeInEnabled,
                               boolean openingBargeInEnabled, String bargeInMode, int bargeInGraceMs,
                               String bargeInStrategy, String bargeInControlText,
                               int bargeInControlMaxCount, int bargeInControlCooldownMs) {
            this.tenantId = tenantId;
            this.nodeId = nodeId;
            this.agentId = agentId;
            this.businessCallId = businessCallId;
            this.customerLegUuid = customerLegUuid;
            this.agentName = agentName;
            this.ttsVoice = ttsVoice;
            this.entity = entity;
            this.openingPreplayed = openingPreplayed;
            this.voiceTransport = voiceTransport == null ? VoiceTransport.HTTP : voiceTransport;
            this.voiceTransportWsUrl = voiceTransportWsUrl;
            this.bargeInEnabled = bargeInEnabled;
            this.openingBargeInEnabled = openingBargeInEnabled;
            String normalizedBargeInMode = StringUtils.blankToDefault(bargeInMode, "STANDARD").toUpperCase(Locale.ROOT);
            this.bargeInMode = Set.of("SENSITIVE", "STANDARD", "NOISY").contains(normalizedBargeInMode)
                ? normalizedBargeInMode : "STANDARD";
            this.bargeInGraceMs = Math.max(0, Math.min(5000, bargeInGraceMs));
            String normalizedStrategy = StringUtils.blankToDefault(bargeInStrategy, "INTERRUPT").toUpperCase(Locale.ROOT);
            this.bargeInStrategy = Set.of("INTERRUPT", "CONTROL_RESUME").contains(normalizedStrategy)
                ? normalizedStrategy : "INTERRUPT";
            this.bargeInControlText = StringUtils.defaultIfBlank(bargeInControlText,
                "您先别急，请让我把这段内容说明完，很快就好。");
            this.bargeInControlMaxCount = Math.max(1, Math.min(20, bargeInControlMaxCount));
            this.bargeInControlCooldownMs = Math.max(0, Math.min(60000, bargeInControlCooldownMs));
            this.waitingSpeakComplete.set(openingPreplayed);
            this.lastActivityAt = LocalDateTime.now();
        }

        private void touch() {
            this.lastActivityAt = LocalDateTime.now();
        }

        private boolean acceptRecognition() {
            return turnInProgress.compareAndSet(false, true);
        }

        private synchronized boolean claimRecognitionEvent(String eventId) {
            if (StringUtils.isBlank(eventId)) {
                return true;
            }
            if (!recentRecognitionEventIds.add(eventId)) {
                return false;
            }
            while (recentRecognitionEventIds.size() > MAX_RECENT_RECOGNITION_EVENTS) {
                var iterator = recentRecognitionEventIds.iterator();
                iterator.next();
                iterator.remove();
            }
            return true;
        }

        private void deferRecognition(String text, long receivedAtNanos, LocalDateTime recognizedAt,
                                      boolean transcriptPersisted) {
            if (StringUtils.isBlank(text)) {
                return;
            }
            deferredRecognition.offer(new DeferredRecognition(text.trim(), receivedAtNanos,
                recognizedAt == null ? LocalDateTime.now() : recognizedAt, transcriptPersisted));
        }

        private DeferredRecognition pollDeferredRecognition() {
            return deferredRecognition.poll();
        }

        private void clearDeferredRecognition() {
            deferredRecognition.clear();
        }
    }

    private boolean isEndedCall(String customerLegUuid) {
        return StringUtils.isNotBlank(customerLegUuid)
            && RedisUtils.hasKey(ENDED_CALL_UUID_KEY_PREFIX + customerLegUuid);
    }

    private void markCallEnded(String customerLegUuid) {
        if (StringUtils.isNotBlank(customerLegUuid)) {
            RedisUtils.setCacheObject(ENDED_CALL_UUID_KEY_PREFIX + customerLegUuid, Boolean.TRUE, ENDED_CALL_TTL);
        }
    }

    record DeferredRecognition(String text, long receivedAtNanos, LocalDateTime recognizedAt,
                               boolean transcriptPersisted) {
    }

    static final class DeferredRecognitionSlot {
        private final AtomicReference<DeferredRecognition> value = new AtomicReference<>();

        void offer(DeferredRecognition recognition) {
            if (recognition != null) {
                value.set(recognition);
            }
        }

        DeferredRecognition poll() {
            return value.getAndSet(null);
        }

        void clear() {
            value.set(null);
        }
    }
}
