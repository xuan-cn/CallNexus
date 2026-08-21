package org.dromara.call.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentConsultCall;
import org.dromara.agent.domain.AgentConsultCallStatus;
import org.dromara.agent.domain.AgentCallOperation;
import org.dromara.agent.domain.AgentCallPhase;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;
import org.dromara.agent.service.AgentRealtimeQueryService;
import org.dromara.agent.runtime.AgentQueueRuntimeStatus;
import org.dromara.agent.service.CallQueueRuntimeSyncService;
import org.dromara.ai.service.AiRealtimeMrcpEventService;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.domain.event.CallLifecycleEvent;
import org.dromara.call.domain.response.CallRealtimeMessage;
import org.dromara.call.service.TelephonyEventHandler;
import org.dromara.call.service.CallConferenceApplicationService;
import org.dromara.call.service.CallRecordApplicationService;
import org.dromara.call.service.CallStateRuntimeService;
import org.dromara.call.service.DispatchCallTaskService;
import org.dromara.call.service.QueueEventApplicationService;
import org.dromara.call.service.TelephonyEndpointIdentityResolver;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.sse.dto.SseMessageDto;
import org.dromara.common.sse.utils.SseMessageUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.websocket.dto.WebSocketMessageDto;
import org.dromara.common.websocket.utils.WebSocketUtils;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.number.domain.request.PhoneNumberNormalizeRequest;
import org.dromara.resource.number.domain.response.PhoneNumberNormalizeResponse;
import org.dromara.resource.number.service.PhoneNumberNormalizationService;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelephonyEventHandlerImpl implements TelephonyEventHandler {
    private static final String ACTIVE_CALL_KEY_PREFIX = "callnexus:agent:active-call:";
    private static final String CONSULT_CALL_KEY_PREFIX = "callnexus:agent:consult-call:";
    private static final String CONSULT_LEG_KEY_PREFIX = "callnexus:call:consult-leg:";
    private static final String CALL_UUID_ACTIVE_CALL_KEYS_PREFIX = "callnexus:call:uuid-active-agents:";
    private static final String CALL_UUID_TARGETS_KEY_PREFIX = "callnexus:call:uuid-targets-v2:";
    private static final String CALL_UUID_ANSWERED_TARGETS_KEY_PREFIX = "callnexus:call:uuid-answered-targets:";
    private static final String ENDED_CALL_UUID_KEY_PREFIX = "callnexus:call:ended-uuid:";
    private static final String TRANSFERRED_SOURCE_AGENT_KEY_PREFIX = "callnexus:call:transferred-source-agent:";
    private static final String TRANSFERRED_SOURCE_EXTENSION_KEY_PREFIX = "callnexus:call:transferred-source-extension:";
    private static final String TRANSFERRED_SOURCE_LEG_KEY_PREFIX = "callnexus:call:transferred-source-leg:";
    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";
    private static final String CALL_STATE_VERSION_KEY_PREFIX = "callnexus:agent:call-state-version:";
    private static final Duration ACTIVE_CALL_TTL = Duration.ofHours(4);
    private static final Duration PRESENCE_TTL = Duration.ofHours(12);
    private static final Duration ENDED_CALL_TTL = Duration.ofSeconds(30);

    private final AgentRealtimeQueryService agentQueryService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway telephonyCommandGateway;
    private final CallQueueRuntimeSyncService queueRuntimeSyncService;
    private final CallRecordApplicationService callRecordApplicationService;
    private final CallStateRuntimeService callStateRuntimeService;
    private final DispatchCallTaskService dispatchCallTaskService;
    private final QueueEventApplicationService queueEventApplicationService;
    private final AiRealtimeMrcpEventService aiRealtimeMrcpEventService;
    private final CallRealtimeTranscriptEventService callRealtimeTranscriptEventService;
    private final CallRealtimeAsrControlService callRealtimeAsrControlService;
    private final PhoneNumberNormalizationService phoneNumberNormalizationService;
    private final TelephonyEndpointIdentityResolver endpointIdentityResolver;
    private final CallConferenceApplicationService callConferenceApplicationService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void onEvent(TelephonyEvent event) {
        // mod_callcenter 队列事件走独立的队列事件处理服务，不参与坐席实时状态机和 WebSocket 推送。
        if (EslEventNames.CUSTOM.equals(event.eventName())) {
            if (EslEventNames.isQueueSatisfactionEvent(event.eventName(), event.eventSubclass())) {
                try {
                    queueEventApplicationService.recordQueueSatisfaction(event);
                } catch (Exception exception) {
                    log.error("队列满意度评价落库失败，不影响通话结束处理，nodeId={}，uuid={}",
                        event.nodeId(), event.uuid(), exception);
                }
                return;
            }
            if (!EslEventNames.isCallCenterQueueEvent(event.eventName(), event.eventSubclass())) {
                return;
            }
            try {
                queueEventApplicationService.handleQueueEvent(event);
            } catch (Exception exception) {
                log.error("队列事件落库失败，不影响坐席实时状态处理，nodeId={}，subclass={}，uuid={}",
                    event.nodeId(), event.eventSubclass(), event.uuid(), exception);
            }
            publishCallCenterAgentRealtimeEvent(event);
            return;
        }
        // DTMF 按键事件单独走队列通话按键采集，不进入通话记录/状态机/实时推送流程。
        if (EslEventNames.DTMF.equals(event.eventName())) {
            String digit = event.headers().get(EslHeaders.DTMF_DIGIT);
            String source = event.headers().get(EslHeaders.DTMF_SOURCE);
            try {
                queueEventApplicationService.recordQueueDtmfIfApplicable(event.uuid(), digit, source);
            } catch (Exception exception) {
                log.warn("处理通话 DTMF 按键事件失败，不影响实时通话状态处理，nodeId={}，uuid={}，digit={}",
                    event.nodeId(), event.uuid(), digit, exception);
            }
            return;
        }
        if (isDispatchSupervisionEvent(event)) {
            try {
                callStateRuntimeService.handleEvent(event);
            } catch (Exception exception) {
                log.error("调度监听或耳语电话腿状态写入失败，nodeId={}，eventName={}，uuid={}",
                    event.nodeId(), event.eventName(), event.uuid(), exception);
            }
            return;
        }
        try {
            aiRealtimeMrcpEventService.handle(event.nodeId(), event.eventName(), event.uuid(), event.headers());
        } catch (Exception exception) {
            log.error("AI UniMRCP 实时语音事件处理失败，不影响通话主流程，nodeId={}，eventName={}，uuid={}，error={}",
                event.nodeId(), event.eventName(), event.uuid(), exception.getMessage(), exception);
        }
        try {
            callRealtimeTranscriptEventService.handle(event);
        } catch (Exception exception) {
            log.error("Realtime call transcript event failed, nodeId={}, eventName={}, uuid={}",
                event.nodeId(), event.eventName(), event.uuid(), exception);
        }
        try {
            callRealtimeAsrControlService.handle(event);
        } catch (Exception exception) {
            log.error("Realtime call ASR control failed, nodeId={}, eventName={}, uuid={}",
                event.nodeId(), event.eventName(), event.uuid(), exception);
        }
        // Dialplan application events are high-volume process signals. They are consumed above by
        // the UniMRCP integration when needed, but must not block lifecycle events behind database,
        // Redis, target resolution, and WebSocket/SSE work.
        if (EslEventNames.CHANNEL_EXECUTE.equals(event.eventName())
            || EslEventNames.CHANNEL_EXECUTE_COMPLETE.equals(event.eventName())) {
            return;
        }
        try {
            callRecordApplicationService.handleEvent(event);
        } catch (Exception exception) {
            log.error("通话记录事件落库失败，不影响实时通话状态处理，nodeId={}，eventName={}，uuid={}",
                event.nodeId(), event.eventName(), event.uuid(), exception);
        }
        try {
            callStateRuntimeService.handleEvent(event);
        } catch (Exception exception) {
            log.error("稳定通话状态写入失败，不影响实时通话状态处理，nodeId={}，eventName={}，uuid={}",
                event.nodeId(), event.eventName(), event.uuid(), exception);
        }
        if (EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
            handleQueueAgentBridge(event);
        }
        try {
            dispatchCallTaskService.handleEvent(event);
        } catch (Exception exception) {
            log.error("调度呼叫任务状态更新失败，不影响通话实时状态处理，nodeId={}，eventName={}，uuid={}",
                event.nodeId(), event.eventName(), event.uuid(), exception);
        }
        handleConsultLifecycleEvent(event);
        if (EslEventNames.isTerminalEvent(event.eventName())
            && !EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            return;
        }
        if (!EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName()) && isEndedCallEvent(event)) {
            return;
        }
        boolean dispatchParticipantEvent = isDispatchCallParticipantEvent(event);
        Map<Long, AgentRealtimeTargetResponse> targets = dispatchParticipantEvent
            ? resolveDispatchParticipantTarget(event)
            : resolveTargets(event);
        if (!dispatchParticipantEvent) {
            mergeMappedTargets(event, targets);
        }
        AgentRealtimeTargetResponse channelOwner = resolveChannelOwnerTarget(event);
        removeConsultSourceTarget(event, targets);
        removeTransferredSourceTargets(event, targets);
        boolean transferredSourceLegHangup = isTransferredSourceLegHangup(event);
        if (transferredSourceLegHangup) {
            log.debug("忽略已转接源坐席通道挂断实时事件，uuid={}，relatedUuids={}",
                event.uuid(), relatedUuids(event));
            targets.clear();
        }
        boolean survivingRelatedLeg = EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())
            && hasSurvivingRelatedLeg(event);
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            try {
                callConferenceApplicationService.handleMemberHangup(event.nodeId(), event.uuid());
            } catch (Exception exception) {
                log.error("会议成员挂机联动处理失败，不影响普通通话结束流程，nodeId={}，uuid={}",
                    event.nodeId(), event.uuid(), exception);
            }
            log.info("Processing FreeSWITCH hangup event, uuid={}, relatedUuids={}, matchedAgents={}, cause={}",
                event.uuid(), relatedUuids(event), targets.keySet(), event.hangupCause());
        }
        if (targets.isEmpty()) {
            log.debug("通话实时事件未匹配到坐席，不推送前端，nodeId={}，eventName={}，uuid={}，callerNumber={}，destinationNumber={}",
                event.nodeId(), event.eventName(), event.uuid(), event.callerNumber(), event.destinationNumber());
        }
        AgentRealtimeTargetResponse lifecycleTarget = targets.values().stream().findFirst().orElse(null);
        if (!survivingRelatedLeg) {
            publishLifecycleEvent(event, lifecycleTarget);
        } else {
            log.info("电话腿已结束但业务通话仍有存活腿，暂不发布整通挂机事件，uuid={}，relatedUuids={}",
                event.uuid(), relatedUuids(event));
        }
        Map<Long, AgentRealtimeTargetResponse> acceptedTargets = new LinkedHashMap<>();
        boolean authoritativeOwnerRequired = requiresAuthoritativeChannelOwner(event);
        if (authoritativeOwnerRequired && channelOwner == null && !targets.isEmpty()) {
            log.warn("实时事件缺少权威坐席电话腿，不更新坐席状态，nodeId={}，eventName={}，uuid={}，candidateExtensions={}",
                event.nodeId(), event.eventName(), event.uuid(),
                targets.values().stream().map(AgentRealtimeTargetResponse::getExtension).toList());
        }
        for (AgentRealtimeTargetResponse target : targets.values()) {
            boolean channelOwnerTarget = channelOwner != null
                && channelOwner.getAgentId().equals(target.getAgentId());
            if (authoritativeOwnerRequired && channelOwner == null) {
                continue;
            }
            if (channelOwner != null && isChannelOwnerOnlyRealtimeEvent(event) && !channelOwnerTarget) {
                log.debug("忽略非当前电话腿所有者的实时事件，uuid={}，eventName={}，ownerExtension={}，targetExtension={}",
                    event.uuid(), event.eventName(), channelOwner.getExtension(), target.getExtension());
                continue;
            }
            if (isStaleTopologyTarget(event, target, channelOwnerTarget)) {
                log.debug("忽略历史映射带出的非当前通话拓扑事件，uuid={}，eventName={}，agentId={}，extension={}",
                    event.uuid(), event.eventName(), target.getAgentId(), target.getExtension());
                continue;
            }
            if (isTransferredSourceTarget(target, event.uuid())) {
                RedisUtils.deleteObject(activeCallKey(target));
                log.info("忽略已转接源坐席的后续实时事件，不向前端发布，uuid={}，relatedUuids={}，agentId={}，extension={}，eventName={}",
                    event.uuid(), relatedUuids(event), target.getAgentId(), target.getExtension(), event.eventName());
                continue;
            }
            if (!channelOwnerTarget && isStaleMappedHangupForTarget(event, target)) {
                log.debug("忽略历史映射带出的非当前坐席挂断事件，uuid={}，targetExtension={}，callerNumber={}，destinationNumber={}",
                    event.uuid(), target.getExtension(), event.callerNumber(), event.destinationNumber());
                continue;
            }
            boolean publishToTarget = true;
            if (channelOwner == null || channelOwnerTarget) {
                publishToTarget = TenantHelper.dynamic(target.getTenantId(), () -> updateTargetState(event, target));
            }
            if (!publishToTarget) {
                continue;
            }
            acceptedTargets.put(target.getAgentId(), target);
            String realtimeMessage = JsonUtils.toJsonString(toMessage(event, target));
            publishRealtimeMessage(target.getUserId(), realtimeMessage);
        }
        if (isConnectedEvent(event) && !acceptedTargets.isEmpty()) {
            saveAnsweredTargets(event, acceptedTargets.values());
        }
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            if (transferredSourceLegHangup || survivingRelatedLeg) {
                deleteSingleUuidRuntimeMappings(event.uuid());
            } else {
                markCallEnded(event);
                deleteUuidMappings(event);
                deleteAnsweredTargetMappings(event);
                deleteUuidActiveCallIndexes(event);
            }
        } else if (!acceptedTargets.isEmpty()) {
            saveUuidMappings(event, acceptedTargets.values());
        }
    }

    private void publishRealtimeMessage(Long userId, String realtimeMessage) {
        WebSocketMessageDto webSocketMessage = new WebSocketMessageDto();
        webSocketMessage.setSessionKeys(List.of(userId));
        webSocketMessage.setMessage(realtimeMessage);
        WebSocketUtils.publishMessage(webSocketMessage);

        SseMessageDto sseMessage = new SseMessageDto();
        sseMessage.setUserIds(List.of(userId));
        sseMessage.setMessage(realtimeMessage);
        SseMessageUtils.publishMessage(sseMessage);
    }

    private boolean isDispatchSupervisionEvent(TelephonyEvent event) {
        String purpose = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE);
        return "DISPATCH_MONITOR".equalsIgnoreCase(purpose)
            || "DISPATCH_WHISPER".equalsIgnoreCase(purpose)
            || "DISPATCH_BARGE".equalsIgnoreCase(purpose);
    }

    private boolean isDispatchCallParticipantEvent(TelephonyEvent event) {
        String purpose = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE);
        return "DISPATCH_CALL_OPERATOR".equalsIgnoreCase(purpose)
            || "DISPATCH_CALL_TARGET".equalsIgnoreCase(purpose)
            || "DISPATCH_INTERCOM_OPERATOR".equalsIgnoreCase(purpose)
            || "DISPATCH_INTERCOM_TARGET".equalsIgnoreCase(purpose)
            || "DISPATCH_BROADCAST_TARGET".equalsIgnoreCase(purpose);
    }

    private Map<Long, AgentRealtimeTargetResponse> resolveDispatchParticipantTarget(TelephonyEvent event) {
        Map<Long, AgentRealtimeTargetResponse> targets = new LinkedHashMap<>();
        String participantExtension = normalizeExtension(
            event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLED));
        addTargetByExtension(targets, event.nodeId(), participantExtension);
        if (targets.isEmpty()) {
            log.debug("调度呼叫参与腿未匹配到坐席，仅维护分机与任务状态，nodeId={}，eventName={}，uuid={}，purpose={}，extension={}",
                event.nodeId(), event.eventName(), event.uuid(),
                event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE), participantExtension);
        }
        return targets;
    }

    private boolean updateTargetState(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        Set<String> relatedUuids = relatedUuids(event);
        if (isTransferredSourceTarget(target, event.uuid())) {
            RedisUtils.deleteObject(activeCallKey(target));
            log.info("忽略已转接源坐席的后续实时事件，避免源坐席重新进入通话状态，uuid={}，relatedUuids={}，agentId={}，extension={}，eventName={}",
                event.uuid(), relatedUuids, target.getAgentId(), target.getExtension(), event.eventName());
            return false;
        }
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            String activeCallKey = activeCallKey(target);
            AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey);
            if (activeCall != null && !matchesEndedCall(event, relatedUuids, activeCall)) {
                log.debug("忽略非当前活动通话的挂断事件，uuid={}，relatedUuids={}，agentId={}，extension={}，activeCallId={}，activeRelatedUuids={}",
                    event.uuid(), relatedUuids, target.getAgentId(), target.getExtension(), activeCall.getCallId(), activeCall.getRelatedUuids());
                return false;
            }
            if (activeCall != null && isOwnAgentLegStillActive(event, activeCall)) {
                if (isCanonicalCustomerLegHangup(event, activeCall)) {
                    hangupSurvivingAgentLeg(event, activeCall, target);
                } else {
                    log.info("关联电话腿挂机但坐席自身电话腿仍存活，保留活动通话状态且不推送挂机事件，uuid={}，agentId={}，extension={}，agentLegUuid={}",
                        event.uuid(), target.getAgentId(), target.getExtension(), activeCall.getAgentChannelId());
                    return false;
                }
            }
            deleteUuidActiveCallIndexes(event, activeCall);
            RedisUtils.deleteObject(activeCallKey);
            // 已接听的坐席挂断后进入话后整理，把本次通话 channel UUID 记录到 presence，
            // 供话后整理时长按实际接听队列计算；整理结束恢复 IDLE 时清空。
            String handlingCallId = wasAnswered(event, target) ? event.uuid() : null;
            updatePresence(target, wasAnswered(event, target) ? AgentPresenceStatus.AFTER_CALL : AgentPresenceStatus.IDLE, handlingCallId);
        } else if (isConnectedEvent(event)) {
            if (!saveActiveCallIfAbsent(event, target, true)) {
                return false;
            }
            updatePresence(target, AgentPresenceStatus.BUSY, null);
        }
        return true;
    }

    private void handleQueueAgentBridge(TelephonyEvent event) {
        String peerUuid = event.headers().get(EslHeaders.OTHER_LEG_UNIQUE_ID);
        if (!isUuid(event.uuid()) || !isUuid(peerUuid) || event.uuid().equals(peerUuid)) {
            log.warn("队列坐席接听处理跳过，CHANNEL_BRIDGE 缺少明确桥接对，uuid={}，otherLegUuid={}",
                event.uuid(), peerUuid);
            return;
        }
        try {
            queueEventApplicationService.recordAgentAnswerOnBridge(event.uuid(), peerUuid);
        } catch (Exception exception) {
            log.warn("记录队列坐席接听事件失败，不影响实时通话状态，uuid={}，otherLegUuid={}",
                event.uuid(), peerUuid, exception);
        }
    }

    private boolean isConnectedEvent(TelephonyEvent event) {
        return EslEventNames.CHANNEL_ANSWER.equals(event.eventName())
            || EslEventNames.CHANNEL_BRIDGE.equals(event.eventName());
    }

    private boolean isChannelOwnerOnlyRealtimeEvent(TelephonyEvent event) {
        return EslEventNames.CHANNEL_CREATE.equals(event.eventName())
            || EslEventNames.CHANNEL_PROGRESS.equals(event.eventName())
            || EslEventNames.CHANNEL_PROGRESS_MEDIA.equals(event.eventName())
            || EslEventNames.CHANNEL_ANSWER.equals(event.eventName())
            || EslEventNames.CHANNEL_HOLD.equals(event.eventName())
            || EslEventNames.CHANNEL_UNHOLD.equals(event.eventName())
            || EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName());
    }

    private boolean requiresAuthoritativeChannelOwner(TelephonyEvent event) {
        return EslEventNames.CHANNEL_CREATE.equals(event.eventName())
            || EslEventNames.CHANNEL_PROGRESS.equals(event.eventName())
            || EslEventNames.CHANNEL_PROGRESS_MEDIA.equals(event.eventName())
            || EslEventNames.CHANNEL_ANSWER.equals(event.eventName())
            || EslEventNames.CHANNEL_HOLD.equals(event.eventName())
            || EslEventNames.CHANNEL_UNHOLD.equals(event.eventName());
    }

    private boolean isOwnAgentLegStillActive(TelephonyEvent event, AgentActiveCall activeCall) {
        String agentLegUuid = activeCall.getAgentChannelId();
        if (agentLegUuid == null || agentLegUuid.isBlank() || agentLegUuid.equals(event.uuid())) {
            return false;
        }
        try {
            return telephonyCommandGateway.callExists(endpoint(event.nodeId()), agentLegUuid);
        } catch (RuntimeException exception) {
            log.warn("检查坐席存活电话腿失败，按挂机事件继续收口，nodeId={}，uuid={}，agentLegUuid={}，error={}",
                event.nodeId(), event.uuid(), agentLegUuid, exception.getMessage());
            return false;
        }
    }

    private boolean isCanonicalCustomerLegHangup(TelephonyEvent event, AgentActiveCall activeCall) {
        return event.uuid() != null
            && !event.uuid().isBlank()
            && event.uuid().equals(activeCall.getBusinessCallId());
    }

    private void hangupSurvivingAgentLeg(TelephonyEvent event, AgentActiveCall activeCall,
                                         AgentRealtimeTargetResponse target) {
        try {
            telephonyCommandGateway.hangup(endpoint(event.nodeId()), activeCall.getAgentChannelId());
            log.info("客户主腿已挂机，主动结束仍存活的坐席腿，customerLegUuid={}，agentLegUuid={}，agentId={}，extension={}",
                event.uuid(), activeCall.getAgentChannelId(), target.getAgentId(), target.getExtension());
        } catch (RuntimeException exception) {
            log.warn("客户主腿挂机后结束坐席腿失败，继续清理平台通话状态，customerLegUuid={}，agentLegUuid={}，error={}",
                event.uuid(), activeCall.getAgentChannelId(), exception.getMessage());
        }
    }

    private boolean hasSurvivingRelatedLeg(TelephonyEvent event) {
        try {
            EslEndpoint endpoint = endpoint(event.nodeId());
            return relatedUuids(event).stream()
                .filter(uuid -> !uuid.equals(event.uuid()))
                .anyMatch(uuid -> telephonyCommandGateway.callExists(endpoint, uuid));
        } catch (RuntimeException exception) {
            log.warn("检查业务通话存活电话腿失败，按整通挂机事件继续收口，nodeId={}，uuid={}，error={}",
                event.nodeId(), event.uuid(), exception.getMessage());
            return false;
        }
    }

    private void handleConsultLifecycleEvent(TelephonyEvent event) {
        try {
            if (EslEventNames.CHANNEL_ANSWER.equals(event.eventName())) {
                handleConsultTargetAnswered(event);
            } else if (EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
                handleConsultBridgeConfirmed(event);
            }
        } catch (Exception exception) {
            log.warn("咨询转接事件状态机处理失败，eventName={}，uuid={}，error={}",
                event.eventName(), event.uuid(), exception.getMessage());
        }
    }

    private void handleConsultTargetAnswered(TelephonyEvent event) {
        AgentConsultCall consultCall = readConsultCallByEvent(event);
        if (consultCall == null || !event.uuid().equals(consultCall.getConsultLegUuid())) {
            return;
        }
        if (!canBridgeConsultTargetOnAnswer(consultCall.getStatus())) {
            log.debug("忽略重复或乱序的咨询坐席接听事件，uuid={}，status={}", event.uuid(), consultCall.getStatus());
            return;
        }
        EslEndpoint endpoint = endpoint(consultCall.getNodeId());
        String customerLegUuid = consultCall.getCustomerLegUuid();
        String sourceAgentLegUuid = consultCall.getSourceAgentLegUuid();
        String consultLegUuid = consultCall.getConsultLegUuid();
        if (!telephonyCommandGateway.callExists(endpoint, customerLegUuid)) {
            hangupIfExists(endpoint, consultLegUuid);
            markConsultFailed(consultCall, "客户通话腿不存在，无法建立咨询桥接");
            return;
        }
        if (!telephonyCommandGateway.callExists(endpoint, sourceAgentLegUuid)) {
            hangupIfExists(endpoint, consultLegUuid);
            unholdIfPossible(endpoint, customerLegUuid);
            markConsultFailed(consultCall, "原坐席通话腿不存在，无法建立咨询桥接");
            return;
        }
        consultCall.setStatus(AgentConsultCallStatus.TARGET_ANSWERED);
        consultCall.setAnsweredAt(LocalDateTime.now());
        saveConsultCall(consultCall);
        log.info("咨询坐席已接听，准备由 Java 状态机桥接原坐席和咨询坐席，originalCallId={}，A={}，B={}，C={}，source={}，target={}",
            consultCall.getOriginalCallId(), customerLegUuid, sourceAgentLegUuid, consultLegUuid,
            consultCall.getAgentExtension(), consultCall.getTargetExtension());
        unholdIfPossible(endpoint, sourceAgentLegUuid);
        unholdIfPossible(endpoint, consultLegUuid);
        telephonyCommandGateway.bridgeCalls(endpoint, sourceAgentLegUuid, consultLegUuid);
        consultCall.setStatus(AgentConsultCallStatus.CONSULT_BRIDGING);
        saveConsultCall(consultCall);
    }

    private boolean canBridgeConsultTargetOnAnswer(AgentConsultCallStatus status) {
        return AgentConsultCallStatus.CUSTOMER_HOLDING.equals(status)
            || AgentConsultCallStatus.DIALING.equals(status)
            || AgentConsultCallStatus.TARGET_RINGING.equals(status);
    }

    private void handleConsultBridgeConfirmed(TelephonyEvent event) {
        AgentConsultCall consultCall = readConsultCallByRelatedUuids(event);
        if (consultCall == null || !isBridgePair(event, consultCall.getSourceAgentLegUuid(), consultCall.getConsultLegUuid())) {
            return;
        }
        if (!AgentConsultCallStatus.TARGET_ANSWERED.equals(consultCall.getStatus())
            && !AgentConsultCallStatus.CONSULT_BRIDGING.equals(consultCall.getStatus())) {
            return;
        }
        consultCall.setStatus(AgentConsultCallStatus.CONSULT_TALKING);
        consultCall.setConsultBridgedAt(LocalDateTime.now());
        saveConsultCall(consultCall);
        log.info("咨询桥接已由 CHANNEL_BRIDGE 确认，状态变更为 CONSULT_TALKING，originalCallId={}，A={}，B={}，C={}，source={}，target={}",
            consultCall.getOriginalCallId(), consultCall.getCustomerLegUuid(), consultCall.getSourceAgentLegUuid(),
            consultCall.getConsultLegUuid(), consultCall.getAgentExtension(), consultCall.getTargetExtension());
    }

    private AgentConsultCall readConsultCallByRelatedUuids(TelephonyEvent event) {
        for (String uuid : relatedUuids(event)) {
            AgentConsultCall consultCall = readConsultCallByLeg(uuid);
            if (consultCall != null) {
                return consultCall;
            }
        }
        return null;
    }

    private AgentConsultCall readConsultCallByEvent(TelephonyEvent event) {
        AgentConsultCall consultCall = readConsultCallByLeg(event.uuid());
        if (consultCall != null) {
            return consultCall;
        }
        consultCall = readConsultCallByLeg(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CONSULT_LEG_UUID));
        if (consultCall != null) {
            return consultCall;
        }
        return buildConsultCallFromEvent(event);
    }

    private AgentConsultCall buildConsultCallFromEvent(TelephonyEvent event) {
        if (!"CONSULT".equalsIgnoreCase(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE))) {
            return null;
        }
        String customerLegUuid = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CUSTOMER_LEG_UUID);
        String sourceAgentLegUuid = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_SOURCE_AGENT_LEG_UUID);
        String consultLegUuid = firstNotBlank(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CONSULT_LEG_UUID), event.uuid());
        if (!isUuid(customerLegUuid) || !isUuid(sourceAgentLegUuid) || !isUuid(consultLegUuid)) {
            log.warn("咨询接听事件缺少完整三腿信息，无法重建咨询上下文，uuid={}，customerLeg={}，sourceLeg={}，consultLeg={}",
                event.uuid(), customerLegUuid, sourceAgentLegUuid, consultLegUuid);
            return null;
        }
        String sourceExtension = normalizeExtension(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_SOURCE_AGENT_EXTENSION));
        String targetExtension = normalizeExtension(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_TARGET_AGENT_EXTENSION));
        if (sourceExtension == null || targetExtension == null) {
            log.warn("咨询接听事件缺少源坐席或目标坐席分机，无法重建咨询上下文，uuid={}，sourceExtension={}，targetExtension={}",
                event.uuid(), sourceExtension, targetExtension);
            return null;
        }
        AgentRealtimeTargetResponse sourceAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), sourceExtension);
        if (sourceAgent == null) {
            log.warn("咨询接听事件无法匹配源坐席，无法重建咨询上下文，uuid={}，nodeId={}，sourceExtension={}",
                event.uuid(), event.nodeId(), sourceExtension);
            return null;
        }
        AgentRealtimeTargetResponse targetAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), targetExtension);
        AgentConsultCall consultCall = new AgentConsultCall();
        consultCall.setOriginalCallId(firstNotBlank(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALL_ID), customerLegUuid));
        consultCall.setConsultCallId(firstNotBlank(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CONSULT_CALL_ID), consultLegUuid));
        consultCall.setAgentChannelId(sourceAgentLegUuid);
        consultCall.setCustomerCallId(customerLegUuid);
        consultCall.setSourceAgentCallId(sourceAgentLegUuid);
        consultCall.setTargetAgentCallId(consultLegUuid);
        consultCall.setTenantId(sourceAgent.getTenantId());
        consultCall.setNodeId(event.nodeId());
        consultCall.setCustomerLegUuid(customerLegUuid);
        consultCall.setSourceAgentLegUuid(sourceAgentLegUuid);
        consultCall.setConsultLegUuid(consultLegUuid);
        consultCall.setStatus(AgentConsultCallStatus.DIALING);
        consultCall.setAgentId(sourceAgent.getAgentId());
        consultCall.setAgentExtension(sourceExtension);
        consultCall.setTargetAgentId(targetAgent == null ? null : targetAgent.getAgentId());
        consultCall.setTargetExtension(targetExtension);
        consultCall.setStartedAt(LocalDateTime.now());
        saveConsultCall(consultCall);
        log.warn("未命中咨询 Redis 索引，已从 CHANNEL_ANSWER 事件变量重建咨询上下文，originalCallId={}，A={}，B={}，C={}，source={}，target={}",
            consultCall.getOriginalCallId(), customerLegUuid, sourceAgentLegUuid, consultLegUuid, sourceExtension, targetExtension);
        return consultCall;
    }

    private AgentConsultCall readConsultCallByLeg(String legUuid) {
        if (legUuid == null || legUuid.isBlank()) {
            return null;
        }
        return RedisUtils.getCacheObject(consultLegKey(legUuid));
    }

    private void saveConsultCall(AgentConsultCall consultCall) {
        if (consultCall == null) {
            return;
        }
        if (consultCall.getTenantId() != null && !consultCall.getTenantId().isBlank() && consultCall.getAgentId() != null) {
            RedisUtils.setCacheObject(consultCallKey(consultCall.getTenantId(), consultCall.getAgentId()), consultCall, ACTIVE_CALL_TTL);
        }
        saveConsultLegIndex(consultCall.getCustomerLegUuid(), consultCall);
        saveConsultLegIndex(consultCall.getSourceAgentLegUuid(), consultCall);
        saveConsultLegIndex(consultCall.getConsultLegUuid(), consultCall);
    }

    private void saveConsultLegIndex(String legUuid, AgentConsultCall consultCall) {
        if (legUuid == null || legUuid.isBlank()) {
            return;
        }
        RedisUtils.setCacheObject(consultLegKey(legUuid), consultCall, ACTIVE_CALL_TTL);
    }

    private void markConsultFailed(AgentConsultCall consultCall, String reason) {
        consultCall.setStatus(AgentConsultCallStatus.FAILED);
        saveConsultCall(consultCall);
        log.warn("咨询转接失败，reason={}，originalCallId={}，A={}，B={}，C={}，source={}，target={}",
            reason, consultCall.getOriginalCallId(), consultCall.getCustomerLegUuid(), consultCall.getSourceAgentLegUuid(),
            consultCall.getConsultLegUuid(), consultCall.getAgentExtension(), consultCall.getTargetExtension());
    }

    private void hangupIfExists(EslEndpoint endpoint, String callId) {
        if (callId == null || callId.isBlank() || !telephonyCommandGateway.callExists(endpoint, callId)) {
            return;
        }
        telephonyCommandGateway.hangup(endpoint, callId);
    }

    private void unholdIfPossible(EslEndpoint endpoint, String callId) {
        try {
            if (callId != null && !callId.isBlank() && telephonyCommandGateway.callExists(endpoint, callId)) {
                telephonyCommandGateway.unhold(endpoint, callId);
            }
        } catch (RuntimeException exception) {
            log.warn("咨询转接恢复客户保持失败，callId={}，error={}", callId, exception.getMessage());
        }
    }

    private boolean isBridgePair(TelephonyEvent event, String left, String right) {
        Set<String> uuids = relatedUuids(event);
        return left != null && right != null && uuids.contains(left) && uuids.contains(right);
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }

    private boolean isStaleMappedHangupForTarget(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        if (!EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            return false;
        }
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(target));
        if (activeCall != null && matchesEndedCall(event, relatedUuids(event), activeCall)) {
            return false;
        }
        String extension = normalizeExtension(target.getExtension());
        if (extension == null) {
            return false;
        }
        return !eventEndpointMatchesExtension(event.nodeId(), extension, event.callerNumber())
            && !eventEndpointMatchesExtension(event.nodeId(), extension, event.destinationNumber());
    }

    private boolean isStaleTopologyTarget(TelephonyEvent event, AgentRealtimeTargetResponse target,
                                          boolean channelOwnerTarget) {
        boolean bridgeEvent = EslEventNames.CHANNEL_BRIDGE.equals(event.eventName());
        boolean unbridgeEvent = EslEventNames.CHANNEL_UNBRIDGE.equals(event.eventName());
        if (!bridgeEvent && !unbridgeEvent) {
            return false;
        }
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(target));
        if (activeCall != null && matchesEndedCall(event, relatedUuids(event), activeCall)) {
            return false;
        }
        // A bridge event may establish the channel owner's first active-call snapshot. An unbridge event must
        // never recreate a participant that has already transferred out.
        return unbridgeEvent || !channelOwnerTarget;
    }

    private Map<Long, AgentRealtimeTargetResponse> resolveTargets(TelephonyEvent event) {
        Map<Long, AgentRealtimeTargetResponse> targets = new LinkedHashMap<>();
        addTargetByExtension(targets, event.nodeId(), event.destinationNumber());
        addTargetByExtension(targets, event.nodeId(), event.callerNumber());
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.CALLER_CALLEE_ID_NUMBER));
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.VARIABLE_SIP_TO_USER));
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.VARIABLE_SIP_REQ_USER));
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.VARIABLE_DIALED_USER));
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.VARIABLE_DIALLED_USER));
        addTargetByExtension(targets, event.nodeId(), extensionFromDialString(event.headers().get(EslHeaders.VARIABLE_CURRENT_APPLICATION_DATA)));
        return targets;
    }

    private AgentRealtimeTargetResponse resolveChannelOwnerTarget(TelephonyEvent event) {
        String extension = endpointIdentityResolver.resolveAuthoritativeExtension(event);
        return extension == null ? null : agentQueryService.findByNodeAndExtension(event.nodeId(), extension);
    }

    private void removeConsultSourceTarget(TelephonyEvent event, Map<Long, AgentRealtimeTargetResponse> targets) {
        if (!"CONSULT".equalsIgnoreCase(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE))) {
            return;
        }
        String sourceExtension = normalizeExtension(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLER));
        if (sourceExtension == null) {
            return;
        }
        targets.values().removeIf(target -> sourceExtension.equals(normalizeExtension(target.getExtension())));
    }

    private void removeTransferredSourceTargets(TelephonyEvent event, Map<Long, AgentRealtimeTargetResponse> targets) {
        if (targets.isEmpty()) {
            return;
        }
        targets.values().removeIf(target -> isTransferredSourceTarget(target, event.uuid()));
    }

    private boolean isTransferredSourceTarget(AgentRealtimeTargetResponse target, String eventLegUuid) {
        if (eventLegUuid == null || eventLegUuid.isBlank()) {
            return false;
        }
        if (Boolean.TRUE.equals(RedisUtils.getCacheObject(
            transferredSourceAgentKey(target.getTenantId(), eventLegUuid, target.getAgentId())))) {
            log.debug("过滤已转接源坐席腿的后续实时事件，uuid={}，agentId={}，extension={}",
                eventLegUuid, target.getAgentId(), target.getExtension());
            return true;
        }
        String extension = normalizeExtension(target.getExtension());
        if (extension != null && Boolean.TRUE.equals(RedisUtils.getCacheObject(
            transferredSourceExtensionKey(target.getTenantId(), eventLegUuid, extension)))) {
            log.debug("过滤已转接源坐席腿的后续实时事件，uuid={}，extension={}",
                eventLegUuid, target.getExtension());
            return true;
        }
        return false;
    }

    private boolean isTransferredSourceLegHangup(TelephonyEvent event) {
        return EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())
            && event.uuid() != null
            && !event.uuid().isBlank()
            && Boolean.TRUE.equals(RedisUtils.getCacheObject(transferredSourceLegKey(event.uuid())));
    }

    private void addTargetByExtension(Map<Long, AgentRealtimeTargetResponse> targets, Long nodeId, String extension) {
        String identity = stripDomainIdentity(extension);
        if (identity == null) return;
        AgentRealtimeTargetResponse target = agentQueryService.findByNodeAndExtension(nodeId, identity);
        if (target != null) targets.put(target.getAgentId(), target);
    }

    private void publishCallCenterAgentRealtimeEvent(TelephonyEvent event) {
        if (!EslEventNames.SUBCLASS_CC_RING_AGENT.equals(event.eventSubclass())
            && !EslEventNames.SUBCLASS_CC_AGENT_ANSWER.equals(event.eventSubclass())) {
            return;
        }
        String agentIdentity = stripDomainIdentity(event.headers().get(EslHeaders.CC_AGENT));
        if (agentIdentity == null) return;
        AgentRealtimeTargetResponse target = agentQueryService.findByNodeAndExtension(event.nodeId(), agentIdentity);
        if (target == null) return;
        if (EslEventNames.SUBCLASS_CC_RING_AGENT.equals(event.eventSubclass()) && hasLiveActiveAgentLeg(event, target)) {
            log.info("忽略已接通坐席的迟到队列振铃事件，nodeId={}，uuid={}，agentId={}，extension={}",
                event.nodeId(), event.uuid(), target.getAgentId(), target.getExtension());
            return;
        }
        CallRealtimeMessage message = new CallRealtimeMessage();
        message.setType(EslEventNames.SUBCLASS_CC_AGENT_ANSWER.equals(event.eventSubclass()) ? "CALL_ANSWER" : "CALL_PROGRESS");
        applyRealtimeCallIdentity(message, event, target);
        message.setCallerNumber(event.callerNumber());
        message.setCalledNumber(target.getExtension());
        message.setAgentExtension(target.getExtension());
        message.setOccurredAt(LocalDateTime.now());
        if (EslEventNames.SUBCLASS_CC_AGENT_ANSWER.equals(event.eventSubclass())) {
            TenantHelper.dynamic(target.getTenantId(), () -> {
                saveActiveCallIfAbsent(event, target);
                updatePresence(target, AgentPresenceStatus.BUSY, null);
            });
        }
        applyRealtimeCallState(message, event, target,
            EslEventNames.SUBCLASS_CC_AGENT_ANSWER.equals(event.eventSubclass())
                ? AgentCallPhase.CONNECTED : AgentCallPhase.INCOMING_RINGING);
        enrichCallerLocation(message, target.getTenantId());
        publishRealtimeMessage(target.getUserId(), JsonUtils.toJsonString(message));
        publishLifecycleEvent(event, target);
    }

    private boolean hasLiveActiveAgentLeg(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(target));
        if (activeCall == null || activeCall.getAgentChannelId() == null || activeCall.getAgentChannelId().isBlank()) {
            return false;
        }
        try {
            return telephonyCommandGateway.callExists(endpoint(event.nodeId()), activeCall.getAgentChannelId());
        } catch (RuntimeException exception) {
            log.warn("检查队列振铃事件是否过期失败，按正常振铃事件继续处理，nodeId={}，agentId={}，agentLegUuid={}，error={}",
                event.nodeId(), target.getAgentId(), activeCall.getAgentChannelId(), exception.getMessage());
            return false;
        }
    }

    private String normalizeExtension(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.startsWith("user/")) {
            normalized = normalized.substring("user/".length());
        }
        int atIndex = normalized.indexOf('@');
        if (atIndex > 0) {
            normalized = normalized.substring(0, atIndex);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String stripDomainIdentity(String value) {
        if (value == null || value.isBlank()) return null;
        String identity = value.trim();
        if (identity.startsWith("[")) {
            int close = identity.indexOf(']');
            if (close >= 0) identity = identity.substring(close + 1).trim();
        }
        if (identity.startsWith("user/")) {
            identity = identity.substring("user/".length());
        }
        int atIndex = identity.indexOf('@');
        if (atIndex > 0) {
            identity = identity.substring(0, atIndex);
        }
        return identity.isBlank() ? null : identity;
    }

    private String extensionFromDialString(String value) {
        if (value == null || value.isBlank()) return null;
        int userIndex = value.indexOf("user/");
        if (userIndex < 0) return null;
        return stripDomainIdentity(value.substring(userIndex));
    }

    private CallRealtimeMessage toMessage(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        CallRealtimeMessage message = new CallRealtimeMessage();
        message.setType("CALL_" + event.eventName().replace("CHANNEL_", ""));
        applyRealtimeCallIdentity(message, event, target);
        String originalCaller = firstNotBlank(
            event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLER),
            event.callerNumber());
        String originalCalled = firstNotBlank(
            event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLED),
            event.destinationNumber());
        RealtimeNumbers numbers = resolveRealtimeNumbers(event, target, originalCaller, originalCalled);
        message.setCallerNumber(numbers.callerNumber());
        message.setCalledNumber(numbers.calledNumber());
        message.setAgentExtension(target.getExtension());
        message.setHangupCause(event.hangupCause());
        message.setOccurredAt(LocalDateTime.now());
        applyRealtimeCallState(message, event, target, resolveCallPhase(event, target));
        enrichCallerLocation(message, target.getTenantId());
        return message;
    }

    private AgentCallPhase resolveCallPhase(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        return switch (event.eventName()) {
            case EslEventNames.CHANNEL_ANSWER, EslEventNames.CHANNEL_BRIDGE -> AgentCallPhase.CONNECTED;
            case EslEventNames.CHANNEL_HOLD -> AgentCallPhase.HELD;
            case EslEventNames.CHANNEL_UNHOLD -> AgentCallPhase.CONNECTED;
            case EslEventNames.CHANNEL_HANGUP_COMPLETE -> AgentCallPhase.ENDED;
            case EslEventNames.CHANNEL_CREATE, EslEventNames.CHANNEL_PROGRESS, EslEventNames.CHANNEL_PROGRESS_MEDIA ->
                isIncomingAgentEvent(event, target) ? AgentCallPhase.INCOMING_RINGING : AgentCallPhase.OUTBOUND_DIALING;
            default -> null;
        };
    }

    private boolean isIncomingAgentEvent(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        String extension = normalizeExtension(target.getExtension());
        return extension != null
            && !eventEndpointMatchesExtension(event.nodeId(), extension, event.callerNumber())
            && (eventEndpointMatchesExtension(event.nodeId(), extension, event.destinationNumber())
                || eventEndpointMatchesExtension(event.nodeId(), extension, event.headers().get(EslHeaders.VARIABLE_SIP_TO_USER))
                || eventEndpointMatchesExtension(event.nodeId(), extension, event.headers().get(EslHeaders.VARIABLE_SIP_REQ_USER)));
    }

    private void applyRealtimeCallState(CallRealtimeMessage message, TelephonyEvent event,
                                        AgentRealtimeTargetResponse target, AgentCallPhase phase) {
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(target));
        AgentCallPhase effectivePhase = preserveAuthoritativePhase(activeCall, phase);
        long stateVersion = nextStateVersion(target);
        message.setCallPhase(effectivePhase);
        message.setAgentLegUuid(activeCall == null
            ? resolveAgentChannelId(event, target) : activeCall.getAgentChannelId());
        message.setCallOperation(activeCall == null || activeCall.getCallOperation() == null
            ? AgentCallOperation.NONE : activeCall.getCallOperation());
        message.setStateVersion(stateVersion);
        if (activeCall != null && effectivePhase != AgentCallPhase.ENDED) {
            if (effectivePhase != null) {
                activeCall.setCallPhase(effectivePhase);
            }
            if (activeCall.getCallOperation() == null) {
                activeCall.setCallOperation(AgentCallOperation.NONE);
            }
            activeCall.setStateVersion(stateVersion);
            RedisUtils.setCacheObject(activeCallKey(target), activeCall, ACTIVE_CALL_TTL);
        }
    }

    private AgentCallPhase preserveAuthoritativePhase(AgentActiveCall activeCall, AgentCallPhase eventPhase) {
        if (activeCall == null || activeCall.getCallPhase() == null || eventPhase == null) {
            return eventPhase;
        }
        AgentCallPhase activePhase = activeCall.getCallPhase();
        boolean preConnectEvent = eventPhase == AgentCallPhase.INCOMING_RINGING
            || eventPhase == AgentCallPhase.OUTBOUND_DIALING;
        if (preConnectEvent && (activePhase == AgentCallPhase.OUTBOUND_DIALING
            || activePhase == AgentCallPhase.CONNECTED || activePhase == AgentCallPhase.HELD)) {
            return activePhase;
        }
        return eventPhase;
    }

    private long nextStateVersion(AgentRealtimeTargetResponse target) {
        String key = CALL_STATE_VERSION_KEY_PREFIX + target.getTenantId() + ":" + target.getAgentId();
        var counter = RedisUtils.getClient().getAtomicLong(key);
        long version = counter.incrementAndGet();
        counter.expire(ACTIVE_CALL_TTL);
        return version;
    }

    private void applyRealtimeCallIdentity(CallRealtimeMessage message, TelephonyEvent event,
                                           AgentRealtimeTargetResponse target) {
        message.setCallId(event.uuid());
        message.setLegUuid(event.uuid());
        String businessCallId = firstNotBlank(
            event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_BUSINESS_CALL_ID),
            event.headers().get(EslHeaders.CALLNEXUS_BUSINESS_CALL_ID));
        if (businessCallId == null && target != null) {
            try {
                businessCallId = TenantHelper.dynamic(target.getTenantId(),
                    () -> callStateRuntimeService.resolveCanonicalBusinessCallId(event));
            } catch (RuntimeException exception) {
                log.debug("实时事件解析业务通话ID失败，保留电话腿ID兼容输出，uuid={}，error={}",
                    event.uuid(), exception.getMessage());
            }
        }
        message.setBusinessCallId(firstNotBlank(businessCallId, event.uuid()));
    }

    private RealtimeNumbers resolveRealtimeNumbers(TelephonyEvent event, AgentRealtimeTargetResponse target,
                                                   String originalCaller, String originalCalled) {
        String targetExtension = normalizeExtension(target.getExtension());
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(target));
        if (activeCall != null && activeCall.getDestination() != null && !activeCall.getDestination().isBlank()) {
            String peerNumber = activeCall.getDestination();
            if (targetExtension != null && targetExtension.equals(normalizeExtension(originalCaller))) {
                return new RealtimeNumbers(target.getExtension(), peerNumber);
            }
            return new RealtimeNumbers(peerNumber, target.getExtension());
        }
        if (targetExtension != null && targetExtension.equals(normalizeExtension(originalCaller))) {
            return new RealtimeNumbers(originalCaller, originalCalled);
        }
        if (targetExtension != null && targetExtension.equals(normalizeExtension(originalCalled))) {
            return new RealtimeNumbers(originalCaller, target.getExtension());
        }
        return new RealtimeNumbers(originalCaller, event.destinationNumber());
    }

    private record RealtimeNumbers(String callerNumber, String calledNumber) {
    }

    private void publishLifecycleEvent(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        String eventType = lifecycleEventType(event);
        if (eventType == null) {
            return;
        }
        String tenantId = target == null ? null : target.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            try {
                tenantId = nodeQueryService.findTenantId(event.nodeId());
            } catch (Exception exception) {
                log.warn("OpenAPI 通话事件无法根据 FreeSWITCH 节点解析租户，nodeId={}，eventName={}，uuid={}，error={}",
                    event.nodeId(), event.eventName(), event.uuid(), exception.getMessage());
                return;
            }
        }
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("OpenAPI 通话事件缺少租户信息，nodeId={}，eventName={}，uuid={}",
                event.nodeId(), event.eventName(), event.uuid());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("leg_uuid", event.uuid());
        payload.put("caller_number", event.callerNumber());
        payload.put("called_number", event.destinationNumber());
        payload.put("agent_id", target == null ? null : target.getAgentId());
        payload.put("agent_extension", target == null ? null : target.getExtension());
        payload.put("hangup_cause", event.hangupCause());
        payload.put("event_name", event.eventName());
        payload.put("event_subclass", event.eventSubclass());
        String resolvedTenantId = tenantId;
        String businessCallId = TenantHelper.dynamic(resolvedTenantId,
            () -> callStateRuntimeService.resolveCanonicalBusinessCallId(event));
        applicationEventPublisher.publishEvent(new CallLifecycleEvent(
            tenantId, eventType, businessCallId, event.nodeId(), LocalDateTime.now(), payload));
    }

    private String lifecycleEventType(TelephonyEvent event) {
        if (EslEventNames.SUBCLASS_CC_RING_AGENT.equals(event.eventSubclass())) return "call.ringing";
        if (EslEventNames.SUBCLASS_CC_AGENT_ANSWER.equals(event.eventSubclass())) return "call.answered";
        return switch (event.eventName()) {
            case EslEventNames.CHANNEL_PROGRESS, EslEventNames.CHANNEL_PROGRESS_MEDIA -> "call.ringing";
            case EslEventNames.CHANNEL_ANSWER -> "call.answered";
            case EslEventNames.CHANNEL_BRIDGE -> "call.bridged";
            case EslEventNames.CHANNEL_UNBRIDGE -> "call.unbridged";
            case EslEventNames.CHANNEL_HANGUP_COMPLETE -> "call.hangup";
            default -> null;
        };
    }

    private void enrichCallerLocation(CallRealtimeMessage message, String tenantId) {
        if (message == null || tenantId == null || tenantId.isBlank() || message.getCallerNumber() == null || message.getCallerNumber().isBlank()) {
            return;
        }
        PhoneNumberNormalizeRequest request = new PhoneNumberNormalizeRequest();
        request.setRawNumber(message.getCallerNumber());
        request.setUsage("CALL_REALTIME_LOCATION");
        request.setStripChinaCountryCode(true);
        request.setAddLocalAreaCode(false);
        try {
            PhoneNumberNormalizeResponse location = phoneNumberNormalizationService.normalize(tenantId, request);
            message.setCallerNumberType(location.getNumberType());
            message.setCallerMobileSegment(location.getMobileSegment());
            message.setCallerProvince(location.getProvince());
            message.setCallerCity(location.getCity());
            message.setCallerCarrier(location.getCarrier());
        } catch (Exception exception) {
            log.debug("实时通话号码归属地解析失败，tenantId={}，callerNumber={}，error={}",
                tenantId, message.getCallerNumber(), exception.getMessage());
        }
    }

    private String activeCallKey(AgentRealtimeTargetResponse target) {
        return ACTIVE_CALL_KEY_PREFIX + target.getTenantId() + ":" + target.getAgentId();
    }

    private String consultCallKey(String tenantId, Long agentId) {
        return CONSULT_CALL_KEY_PREFIX + tenantId + ":" + agentId;
    }

    private String consultLegKey(String legUuid) {
        return CONSULT_LEG_KEY_PREFIX + legUuid;
    }

    private void mergeMappedTargets(TelephonyEvent event, Map<Long, AgentRealtimeTargetResponse> targets) {
        for (String uuid : relatedUuids(event)) {
            List<AgentRealtimeTargetResponse> mappedTargets = readMappedTargets(uuid);
            if (mappedTargets != null) {
                mappedTargets.forEach(target -> targets.put(target.getAgentId(), target));
            }
        }
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            mergeActiveCallTargets(event, targets);
        }
    }

    private void saveUuidMappings(TelephonyEvent event, java.util.Collection<AgentRealtimeTargetResponse> targets) {
        List<AgentRealtimeTargetResponse> snapshot = List.copyOf(targets);
        for (String uuid : relatedUuids(event)) {
            List<AgentRealtimeTargetResponse> existing = readMappedTargets(uuid);
            Map<Long, AgentRealtimeTargetResponse> merged = new LinkedHashMap<>();
            if (existing != null) existing.forEach(target -> merged.put(target.getAgentId(), target));
            snapshot.forEach(target -> merged.put(target.getAgentId(), target));
            RedisUtils.setCacheObject(uuidTargetsKey(uuid), JsonUtils.toJsonString(merged.values()), ACTIVE_CALL_TTL);
        }
    }

    private List<AgentRealtimeTargetResponse> readMappedTargets(String uuid) {
        String json = RedisUtils.getCacheObject(uuidTargetsKey(uuid));
        return json == null ? null : JsonUtils.parseArray(json, AgentRealtimeTargetResponse.class);
    }

    private void deleteUuidMappings(TelephonyEvent event) {
        relatedUuids(event).forEach(uuid -> RedisUtils.deleteObject(uuidTargetsKey(uuid)));
    }

    private void saveAnsweredTargets(TelephonyEvent event, Collection<AgentRealtimeTargetResponse> targets) {
        List<AgentRealtimeTargetResponse> snapshot = List.copyOf(targets);
        for (String uuid : relatedUuids(event)) {
            List<AgentRealtimeTargetResponse> existing = readAnsweredTargets(uuid);
            Map<Long, AgentRealtimeTargetResponse> merged = new LinkedHashMap<>();
            if (existing != null) existing.forEach(target -> merged.put(target.getAgentId(), target));
            snapshot.forEach(target -> merged.put(target.getAgentId(), target));
            RedisUtils.setCacheObject(answeredTargetsKey(uuid), JsonUtils.toJsonString(merged.values()), ACTIVE_CALL_TTL);
        }
    }

    private List<AgentRealtimeTargetResponse> readAnsweredTargets(String uuid) {
        String json = RedisUtils.getCacheObject(answeredTargetsKey(uuid));
        return json == null ? null : JsonUtils.parseArray(json, AgentRealtimeTargetResponse.class);
    }

    private boolean wasAnswered(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        for (String uuid : relatedUuids(event)) {
            List<AgentRealtimeTargetResponse> answeredTargets = readAnsweredTargets(uuid);
            if (answeredTargets != null && answeredTargets.stream().anyMatch(item -> item.getAgentId().equals(target.getAgentId()))) {
                return true;
            }
        }
        return false;
    }

    private void deleteAnsweredTargetMappings(TelephonyEvent event) {
        relatedUuids(event).forEach(uuid -> RedisUtils.deleteObject(answeredTargetsKey(uuid)));
    }

    private void deleteSingleUuidRuntimeMappings(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        RedisUtils.deleteObject(uuidTargetsKey(uuid));
        RedisUtils.deleteObject(answeredTargetsKey(uuid));
        RedisUtils.deleteObject(uuidActiveCallKeysKey(uuid));
    }

    private String answeredTargetsKey(String uuid) {
        return CALL_UUID_ANSWERED_TARGETS_KEY_PREFIX + uuid;
    }

    private Set<String> relatedUuids(TelephonyEvent event) {
        Set<String> uuids = new LinkedHashSet<>();
        addUuid(uuids, event.uuid());
        addUuid(uuids, event.headers().get(EslHeaders.OTHER_LEG_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.BRIDGE_A_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.BRIDGE_B_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.CHANNEL_CALL_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_ORIGINATION_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_BRIDGE_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_BUSINESS_CALL_ID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_CC_MEMBER_SESSION_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_CC_MEMBER_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.CC_CALLER_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.CC_MEMBER_UUID));
        return uuids;
    }

    private void addUuid(Set<String> uuids, String uuid) {
        if (uuid != null && !uuid.isBlank()) uuids.add(uuid);
    }

    private String uuidTargetsKey(String uuid) {
        return CALL_UUID_TARGETS_KEY_PREFIX + uuid;
    }

    private boolean isEndedCallEvent(TelephonyEvent event) {
        return relatedUuids(event).stream().anyMatch(uuid -> RedisUtils.hasKey(endedUuidKey(uuid)));
    }

    private void markCallEnded(TelephonyEvent event) {
        relatedUuids(event).forEach(uuid -> RedisUtils.setCacheObject(endedUuidKey(uuid), Boolean.TRUE, ENDED_CALL_TTL));
    }

    private String endedUuidKey(String uuid) {
        return ENDED_CALL_UUID_KEY_PREFIX + uuid;
    }

    private void mergeActiveCallTargets(TelephonyEvent event, Map<Long, AgentRealtimeTargetResponse> targets) {
        Set<String> relatedUuids = relatedUuids(event);
        for (String activeCallKey : activeCallKeysByUuids(relatedUuids)) {
            mergeActiveCallTarget(event, relatedUuids, targets, activeCallKey);
        }
    }

    private Set<String> activeCallKeysByUuids(Set<String> relatedUuids) {
        Set<String> activeCallKeys = new LinkedHashSet<>();
        for (String uuid : relatedUuids) {
            activeCallKeys.addAll(RedisUtils.getCacheSet(uuidActiveCallKeysKey(uuid)));
        }
        return activeCallKeys;
    }

    private void mergeActiveCallTarget(TelephonyEvent event, Set<String> relatedUuids, Map<Long, AgentRealtimeTargetResponse> targets,
                                       String activeCallKey) {
        AgentActiveCall call = RedisUtils.getCacheObject(activeCallKey);
        if (call == null || !matchesEndedCall(event, relatedUuids, call)) return;
        AgentRealtimeTargetResponse target = agentQueryService.findByNodeAndExtension(event.nodeId(), call.getAgentExtension());
        if (target == null) return;
        if (!target.getAgentId().equals(call.getAgentId())) {
            log.warn("挂断事件 activeCall 兜底匹配到异常坐席数据，uuid={}，relatedUuids={}，activeAgentId={}，resolvedAgentId={}，extension={}，activeCallId={}",
                event.uuid(), relatedUuids, call.getAgentId(), target.getAgentId(), call.getAgentExtension(), call.getCallId());
            return;
        }
        targets.put(target.getAgentId(), target);
        log.info("挂断事件按 activeCall 反向索引匹配到坐席，uuid={}，relatedUuids={}，agentId={}，extension={}，activeCallId={}",
            event.uuid(), relatedUuids, target.getAgentId(), target.getExtension(), call.getCallId());
    }

    private boolean matchesEndedCall(TelephonyEvent event, Set<String> relatedUuids, AgentActiveCall call) {
        String eventUuid = event.uuid();
        if (eventUuid == null || eventUuid.isBlank()) return false;
        if (eventUuid.equals(call.getCallId())) return true;
        if (eventUuid.equals(call.getAgentChannelId())) return true;
        return call.getRelatedUuids() != null && call.getRelatedUuids().contains(eventUuid);
    }

    private boolean equalsAny(String source, String... values) {
        if (source == null || source.isBlank()) return false;
        for (String value : values) {
            if (source.equals(value)) return true;
        }
        return false;
    }

    private boolean saveActiveCallIfAbsent(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        return saveActiveCallIfAbsent(event, target, false);
    }

    private boolean saveActiveCallIfAbsent(TelephonyEvent event, AgentRealtimeTargetResponse target,
                                           boolean requireAgentChannel) {
        String key = activeCallKey(target);
        AgentActiveCall existing = RedisUtils.getCacheObject(key);
        String agentChannelId = resolveAgentChannelId(event, target);
        if (existing != null) {
            String previousAgentChannelId = existing.getAgentChannelId();
            boolean agentLegChanged = agentChannelId != null
                && previousAgentChannelId != null
                && !agentChannelId.equals(previousAgentChannelId);
            existing.setCallId(resolvePrimaryCallId(event, existing));
            existing.setBusinessCallId(callStateRuntimeService.resolveBusinessCallId(event, existing));
            if (agentChannelId != null) {
                existing.setAgentChannelId(agentChannelId);
            }
            existing.setDestination(resolvePeerNumber(event, target, existing.getDestination()));
            existing.setRelatedUuids(mergeRelatedUuids(event, existing, agentLegChanged));
            existing.setCallPhase(AgentCallPhase.CONNECTED);
            if (existing.getCallOperation() == null) {
                existing.setCallOperation(AgentCallOperation.NONE);
            }
            RedisUtils.setCacheObject(key, existing, ACTIVE_CALL_TTL);
            saveUuidActiveCallIndexes(event, key);
            return true;
        }
        if (requireAgentChannel && (agentChannelId == null || agentChannelId.isBlank())) {
            log.warn("拒绝创建缺少坐席电话腿的活动通话，nodeId={}，eventName={}，uuid={}，agentId={}，extension={}",
                event.nodeId(), event.eventName(), event.uuid(), target.getAgentId(), target.getExtension());
            return false;
        }
        AgentActiveCall call = new AgentActiveCall();
        call.setCallId(resolvePrimaryCallId(event, null));
        call.setBusinessCallId(callStateRuntimeService.resolveBusinessCallId(event));
        call.setAgentChannelId(agentChannelId);
        call.setAgentId(target.getAgentId());
        call.setAgentExtension(target.getExtension());
        call.setDestination(resolvePeerNumber(event, target, null));
        call.setRelatedUuids(relatedUuids(event));
        call.setCallPhase(AgentCallPhase.CONNECTED);
        call.setCallOperation(AgentCallOperation.NONE);
        RedisUtils.setCacheObject(key, call, ACTIVE_CALL_TTL);
        saveUuidActiveCallIndexes(event, key);
        return true;
    }

    private String resolvePrimaryCallId(TelephonyEvent event, AgentActiveCall existing) {
        if (existing != null && "INTERNAL".equalsIgnoreCase(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_DIRECTION))) {
            return existing.getCallId();
        }
        if (EslEventNames.CHANNEL_BRIDGE.equals(event.eventName()) && isUuid(event.uuid())) {
            return event.uuid();
        }
        return firstNotBlank(
            isUuid(event.uuid()) ? event.uuid() : null,
            event.headers().get(EslHeaders.VARIABLE_ORIGINATION_UUID),
            event.headers().get(EslHeaders.CHANNEL_CALL_UUID),
            isUuid(event.headers().get(EslHeaders.CC_CALLER_UUID)) ? event.headers().get(EslHeaders.CC_CALLER_UUID) : null,
            existing == null ? null : existing.getCallId(),
            event.uuid()
        );
    }

    private String resolvePeerNumber(TelephonyEvent event, AgentRealtimeTargetResponse target, String existingDestination) {
        String extension = normalizeExtension(target.getExtension());
        if (existingDestination != null && !existingDestination.isBlank()) {
            return existingDestination;
        }
        String originalCaller = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLER);
        String originalCalled = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLED);
        if (extension != null && extension.equals(normalizeExtension(originalCaller)) && originalCalled != null && !originalCalled.isBlank()) {
            return originalCalled;
        }
        if (extension != null && extension.equals(normalizeExtension(originalCalled)) && originalCaller != null && !originalCaller.isBlank()) {
            return originalCaller;
        }
        if (extension != null && eventEndpointMatchesExtension(event.nodeId(), extension, event.callerNumber())) {
            return event.destinationNumber();
        }
        return event.callerNumber();
    }

    private String resolveAgentChannelId(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        if (event.uuid() == null || event.uuid().isBlank()) {
            return null;
        }
        if (endpointIdentityResolver.isExternalCounterpartyChannel(event)) {
            return null;
        }
        String extension = normalizeExtension(target.getExtension());
        if (extension == null) {
            return null;
        }
        String authoritativeExtension = endpointIdentityResolver.resolveAuthoritativeExtension(event);
        if (authoritativeExtension != null) {
            return extension.equals(normalizeExtension(authoritativeExtension)) ? event.uuid() : null;
        }
        if (eventEndpointMatchesExtension(event.nodeId(), extension, event.callerNumber())
            || eventEndpointMatchesExtension(event.nodeId(), extension, event.headers().get(EslHeaders.CALLER_CALLEE_ID_NUMBER))
            || eventEndpointMatchesExtension(event.nodeId(), extension, event.headers().get(EslHeaders.VARIABLE_SIP_TO_USER))
            || eventEndpointMatchesExtension(event.nodeId(), extension, event.headers().get(EslHeaders.VARIABLE_SIP_REQ_USER))
            || eventEndpointMatchesExtension(event.nodeId(), extension, event.destinationNumber())) {
            return event.uuid();
        }
        return null;
    }

    private boolean eventEndpointMatchesExtension(Long nodeId, String expectedExtension, String endpointIdentity) {
        String expected = normalizeExtension(expectedExtension);
        String actual = resolveEndpointExtension(nodeId, endpointIdentity);
        return expected != null && expected.equals(normalizeExtension(actual));
    }

    private String resolveEndpointExtension(Long nodeId, String endpointIdentity) {
        String identity = stripDomainIdentity(endpointIdentity);
        if (identity == null) {
            return null;
        }
        AgentRealtimeTargetResponse target = agentQueryService.findByNodeAndExtension(nodeId, identity);
        return target == null ? identity : target.getExtension();
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isUuid(String value) {
        return value != null && value.matches("^[0-9a-fA-F-]{36}$");
    }

    private void saveUuidActiveCallIndexes(TelephonyEvent event, String activeCallKey) {
        for (String uuid : relatedUuids(event)) {
            String key = uuidActiveCallKeysKey(uuid);
            RedisUtils.addCacheSet(key, activeCallKey);
            RedisUtils.expire(key, ACTIVE_CALL_TTL);
        }
    }

    private Set<String> mergeRelatedUuids(TelephonyEvent event, AgentActiveCall activeCall, boolean agentLegChanged) {
        Set<String> uuids = new LinkedHashSet<>(relatedUuids(event));
        if (agentLegChanged) {
            addUuid(uuids, activeCall.getBusinessCallId());
            return uuids;
        }
        if (activeCall != null && activeCall.getRelatedUuids() != null) {
            uuids.addAll(activeCall.getRelatedUuids());
        }
        return uuids;
    }

    private void deleteUuidActiveCallIndexes(TelephonyEvent event) {
        if (event.uuid() != null && !event.uuid().isBlank()) {
            RedisUtils.deleteObject(uuidActiveCallKeysKey(event.uuid()));
        }
    }

    private void deleteUuidActiveCallIndexes(TelephonyEvent event, AgentActiveCall activeCall) {
        Set<String> uuids = new LinkedHashSet<>(relatedUuids(event));
        if (activeCall != null && activeCall.getRelatedUuids() != null) {
            uuids.addAll(activeCall.getRelatedUuids());
        }
        uuids.forEach(uuid -> RedisUtils.deleteObject(uuidActiveCallKeysKey(uuid)));
    }

    private String uuidActiveCallKeysKey(String uuid) {
        return CALL_UUID_ACTIVE_CALL_KEYS_PREFIX + uuid;
    }

    private String transferredSourceAgentKey(String tenantId, String customerCallId, Long agentId) {
        return TRANSFERRED_SOURCE_AGENT_KEY_PREFIX + tenantId + ":" + customerCallId + ":" + agentId;
    }

    private String transferredSourceExtensionKey(String tenantId, String customerCallId, String extension) {
        return TRANSFERRED_SOURCE_EXTENSION_KEY_PREFIX + tenantId + ":" + customerCallId + ":" + extension;
    }

    private String transferredSourceLegKey(String sourceAgentCallId) {
        return TRANSFERRED_SOURCE_LEG_KEY_PREFIX + sourceAgentCallId;
    }

    private void updatePresence(AgentRealtimeTargetResponse target, AgentPresenceStatus status, String handlingCallId) {
        String key = PRESENCE_KEY_PREFIX + target.getTenantId() + ":" + target.getAgentId();
        AgentPresence presence = RedisUtils.getCacheObject(key);
        if (presence == null) return;
        presence.setStatus(status);
        presence.setUpdatedAt(LocalDateTime.now());
        // AFTER_CALL 记录本次通话 channel UUID 用于话后整理时长计算；其余状态清空，避免残留。
        presence.setHandlingCallId(AgentPresenceStatus.AFTER_CALL.equals(status) ? handlingCallId : null);
        RedisUtils.setCacheObject(key, presence, PRESENCE_TTL);
        syncQueueStatus(target, status);
    }

    private void syncQueueStatus(AgentRealtimeTargetResponse target, AgentPresenceStatus status) {
        try {
            if (target.getNodeId() == null || target.getSipDomain() == null || target.getSipDomain().isBlank()) return;
            queueRuntimeSyncService.syncAgentStatus(new AgentQueueRuntimeStatus(
                target.getNodeId(), target.getExtension(), target.getAuthUsername(), target.getSipDomain(), status));
        } catch (Exception exception) {
            log.warn("通话事件同步 FreeSWITCH 队列坐席状态失败，不影响本地坐席状态，agentId={}，status={}，error={}",
                target.getAgentId(), status, exception.getMessage());
        }
    }
}
