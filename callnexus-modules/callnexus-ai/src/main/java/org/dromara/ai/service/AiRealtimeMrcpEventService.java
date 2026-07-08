package org.dromara.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.domain.AiRealtimeCallSession;
import org.dromara.ai.domain.AiRealtimeCallTurn;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.domain.response.AiConversationStartResponse;
import org.dromara.ai.mapper.AiRealtimeCallSessionMapper;
import org.dromara.ai.mapper.AiRealtimeCallTurnMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class AiRealtimeMrcpEventService {
    private static final String TRANSPORT = "UNIMRCP";
    private static final String VAR_CUSTOMER_LEG_UUID = "callnexus_ai_customer_leg_uuid";
    private static final String VAR_BUSINESS_CALL_ID = "callnexus_business_call_id";
    private static final String VAR_AGENT_ID = "callnexus_ai_agent_id";
    private static final String VAR_FLOW_ID = "callnexus_ai_flow_id";
    private static final String VAR_NODE_ID = "callnexus_ai_node_id";
    private static final String VAR_OPENING_PREPLAYED = "callnexus_ai_opening_preplayed";

    private final AiKnowledgeProperties properties;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final AiSpeechProviderSelector speechProviderSelector;
    private final AiAgentApplicationService agentService;
    private final AiRealtimeCallSessionMapper sessionMapper;
    private final AiRealtimeCallTurnMapper turnMapper;
    private final ObjectProvider<AiRealtimeTelephonyGateway> telephonyGatewayProvider;
    @Qualifier("aiRealtimeExecutor")
    private final Executor executor;
    @Qualifier("aiRealtimeScheduler")
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<String, RuntimeSession> sessions = new ConcurrentHashMap<>();

    public AiRealtimeMrcpEventService(AiKnowledgeProperties properties,
                                      FreeSwitchNodeQueryService nodeQueryService,
                                      AiSpeechProviderSelector speechProviderSelector,
                                      AiAgentApplicationService agentService,
                                      AiRealtimeCallSessionMapper sessionMapper,
                                      AiRealtimeCallTurnMapper turnMapper,
                                      ObjectProvider<AiRealtimeTelephonyGateway> telephonyGatewayProvider,
                                      @Qualifier("aiRealtimeExecutor") Executor executor,
                                      @Qualifier("aiRealtimeScheduler") ThreadPoolTaskScheduler scheduler) {
        this.properties = properties;
        this.nodeQueryService = nodeQueryService;
        this.speechProviderSelector = speechProviderSelector;
        this.agentService = agentService;
        this.sessionMapper = sessionMapper;
        this.turnMapper = turnMapper;
        this.telephonyGatewayProvider = telephonyGatewayProvider;
        this.executor = executor;
        this.scheduler = scheduler;
    }

    public void handle(Long nodeId, String eventName, String uuid, Map<String, String> headers) {
        long receivedAtNanos = System.nanoTime();
        if (!Boolean.TRUE.equals(properties.getRealtimeEnabled()) || !isUniMrcpEvent(headers)) {
            return;
        }
        String tenantId = nodeQueryService.findTenantId(nodeId);
        if (StringUtils.isBlank(tenantId)) {
            log.warn("AI UniMRCP 事件缺少租户上下文，nodeId={}，uuid={}，eventName={}", nodeId, uuid, eventName);
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
            onSpeakComplete(runtime);
            return;
        }
        String result = recognizedText(headers);
        if ("DETECTED_SPEECH".equals(eventName) && StringUtils.isBlank(result)) {
            log.warn("收到 DETECTED_SPEECH 但未找到识别文本，sessionId={}，businessCallId={}，候选字段={}，speech相关事件头={}",
                runtime.entity.getId(), runtime.businessCallId, properties.getUnimrcp().getResultHeaderCandidates(),
                speechRelatedHeaders(headers));
        }
        if (StringUtils.isNotBlank(result) && runtime.acceptRecognition(result)) {
            runtime.recognizing.set(false);
            log.info("AI UniMRCP 识别到用户语音，sessionId={}，businessCallId={}，text={}",
                runtime.entity.getId(), runtime.businessCallId, result.trim());
            executor.execute(() -> TenantHelper.dynamic(tenantId, () -> processTurn(runtime, result.trim())));
        }
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
        log.info("AI UniMRCP 运行会话已创建，sessionId={}，businessCallId={}，customerLegUuid={}，agentId={}，asrProviderId={}，ttsProviderId={}，costMs={}",
            entity.getId(), businessCallId, customerLegUuid, agentId, asrProvider.getId(), ttsProvider.getId(), elapsedMillis(startNanos));
        return new RuntimeSession(tenantId, nodeId, agentId, businessCallId, customerLegUuid,
            ttsProvider.getDefaultVoice(), entity, openingPreplayed);
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
            log.info("AI UniMRCP 开始建立会话，sessionId={}，businessCallId={}，customerLegUuid={}，agentId={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, runtime.agentId);
            long conversationNanos = System.nanoTime();
            AiConversationStartResponse start = agentService.startRealtimeConversation(runtime.agentId);
            long conversationCostMs = elapsedMillis(conversationNanos);
            long updateNanos = System.nanoTime();
            runtime.entity.setConversationId(Long.valueOf(String.valueOf(start.getConversation().getId())));
            sessionMapper.updateById(runtime.entity);
            long updateCostMs = elapsedMillis(updateNanos);
            runtime.conversationReady.set(true);
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

    private void processTurn(RuntimeSession runtime, String text) {
        AiRealtimeCallTurn turn = new AiRealtimeCallTurn();
        turn.setRealtimeSessionId(runtime.entity.getId());
        turn.setSequenceNo(runtime.sequence.incrementAndGet());
        turn.setUserText(text);
        turn.setTurnState("THINKING");
        turn.setRecognizedAt(LocalDateTime.now());
        turnMapper.insert(turn);
        updateState(runtime, "THINKING", null);
        long chatNanos = System.nanoTime();
        try {
            AiChatTurnResult result = agentService.chatOnce(runtime.agentId, runtime.entity.getConversationId(), text);
            long chatCostMs = elapsedMillis(chatNanos);
            runtime.entity.setConversationId(result.conversationId());
            turn.setAssistantText(result.answer());
            turn.setAnswerSource(result.sourceType());
            turn.setAnsweredAt(LocalDateTime.now());
            turn.setTurnState("SPEAKING");
            turnMapper.updateById(turn);
            sessionMapper.updateById(runtime.entity);
            log.info("AI UniMRCP 轮次回答完成，sessionId={}，businessCallId={}，turn={}，source={}，answerLength={}，chatCostMs={}",
                runtime.entity.getId(), runtime.businessCallId, turn.getSequenceNo(), result.sourceType(),
                result.answer() == null ? 0 : result.answer().length(), chatCostMs);
            speak(runtime, result.answer(), turn);
        } catch (Exception exception) {
            turn.setTurnState("FAILED");
            turn.setFailureReason(limit(exception.getMessage()));
            turnMapper.updateById(turn);
            runtime.turnInProgress.set(false);
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
        scheduler.schedule(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (!runtime.waitingSpeakComplete.compareAndSet(true, false)) {
                return;
            }
            log.warn("AI UniMRCP 未收到首句播报完成事件，按估算时长启动识别，sessionId={}，businessCallId={}，customerLegUuid={}，delayMs={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, delay);
            markListening(runtime);
            tryRecognize(runtime);
        }), Instant.now().plusMillis(delay));
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
        if (turn != null) {
            turn.setPlaybackStartedAt(LocalDateTime.now());
            turnMapper.updateById(turn);
        }
        long gatewayNanos = System.nanoTime();
        log.info("AI UniMRCP 准备提交播报，sessionId={}，businessCallId={}，customerLegUuid={}，textLength={}，voice={}，stateCostMs={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, text.length(), runtime.ttsVoice, stateCostMs);
        gateway().speak(runtime.nodeId, runtime.customerLegUuid, text, runtime.ttsVoice);
        long gatewayCostMs = elapsedMillis(gatewayNanos);
        long delay = estimateSpeakDelay(text) + properties.getUnimrcp().getSpeakCompleteDelayMs();
        scheduler.schedule(() -> TenantHelper.dynamic(runtime.tenantId, () -> {
            if (!runtime.waitingSpeakComplete.compareAndSet(true, false)) {
                return;
            }
            if (turn != null && !"COMPLETED".equals(turn.getTurnState())) {
                turn.setTurnState("COMPLETED");
                turn.setPlaybackEndedAt(LocalDateTime.now());
                turnMapper.updateById(turn);
            }
            markListening(runtime);
            tryRecognize(runtime);
        }), Instant.now().plusMillis(delay));
        log.info("AI UniMRCP 已提交播报，sessionId={}，businessCallId={}，customerLegUuid={}，textLength={}，delayMs={}，gatewayCostMs={}，totalCostMs={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, text.length(), delay,
            gatewayCostMs, elapsedMillis(speakNanos));
    }

    private void onSpeakComplete(RuntimeSession runtime) {
        if (runtime.openingPreplayed && !runtime.conversationReady.get()) {
            runtime.preplayedOpeningCompleted.set(true);
            runtime.waitingSpeakComplete.set(false);
            log.info("AI UniMRCP 首句播报已完成，会话仍在初始化，等待会话创建后启动识别，sessionId={}，businessCallId={}，customerLegUuid={}",
                runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid);
            return;
        }
        if (!runtime.waitingSpeakComplete.compareAndSet(true, false)) {
            return;
        }
        log.info("AI UniMRCP 收到播报完成，sessionId={}，businessCallId={}，customerLegUuid={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid);
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
        if (!runtime.closed.compareAndSet(false, true)) {
            return;
        }
        runtime.entity.setSessionState("FAILED".equals(runtime.entity.getSessionState()) ? "FAILED" : "ENDED");
        runtime.entity.setEndedAt(LocalDateTime.now());
        runtime.entity.setLastActivityAt(LocalDateTime.now());
        sessionMapper.updateById(runtime.entity);
        log.info("AI UniMRCP 会话结束，sessionId={}，businessCallId={}，customerLegUuid={}，reason={}",
            runtime.entity.getId(), runtime.businessCallId, runtime.customerLegUuid, reason);
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

    private boolean isUniMrcpEvent(Map<String, String> headers) {
        if (TRANSPORT.equalsIgnoreCase(header(headers, "callnexus_ai_transport"))) {
            return true;
        }
        if (StringUtils.isNotBlank(recognizedText(headers))) {
            return true;
        }
        return StringUtils.isNotBlank(header(headers, VAR_CUSTOMER_LEG_UUID))
            && parseLong(header(headers, VAR_AGENT_ID)) != null;
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

    private static final class RuntimeSession {
        private final String tenantId;
        private final Long nodeId;
        private final Long agentId;
        private final String businessCallId;
        private final String customerLegUuid;
        private final String ttsVoice;
        private final AiRealtimeCallSession entity;
        private final boolean openingPreplayed;
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean conversationReady = new AtomicBoolean();
        private final AtomicBoolean preplayedOpeningCompleted = new AtomicBoolean();
        private final AtomicBoolean turnInProgress = new AtomicBoolean();
        private final AtomicBoolean waitingSpeakComplete = new AtomicBoolean();
        private final AtomicBoolean recognizing = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile String lastRecognition;
        private volatile LocalDateTime lastActivityAt;

        private RuntimeSession(String tenantId, Long nodeId, Long agentId, String businessCallId,
                               String customerLegUuid, String ttsVoice, AiRealtimeCallSession entity, boolean openingPreplayed) {
            this.tenantId = tenantId;
            this.nodeId = nodeId;
            this.agentId = agentId;
            this.businessCallId = businessCallId;
            this.customerLegUuid = customerLegUuid;
            this.ttsVoice = ttsVoice;
            this.entity = entity;
            this.openingPreplayed = openingPreplayed;
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
