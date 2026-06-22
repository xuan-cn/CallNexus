package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.agent.domain.AgentConsultCall;
import org.dromara.agent.domain.AgentConsultCallStatus;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.AgentRealtimeQueryService;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.call.domain.CallEvent;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.response.CallRealtimeMessage;
import org.dromara.call.mapper.CallEventMapper;
import org.dromara.call.mapper.CallLegMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.sse.dto.SseMessageDto;
import org.dromara.common.sse.utils.SseMessageUtils;
import org.dromara.common.websocket.dto.WebSocketMessageDto;
import org.dromara.common.websocket.utils.WebSocketUtils;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationCommand;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationResult;
import org.dromara.resource.outboundauth.service.OutboundAuthorizationService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class CallControlApplicationServiceImpl implements CallControlApplicationService {
    private static final String ACTIVE_CALL_KEY_PREFIX = "callnexus:agent:active-call:";
    private static final String CONSULT_CALL_KEY_PREFIX = "callnexus:agent:consult-call:";
    private static final String CONSULT_LEG_KEY_PREFIX = "callnexus:call:consult-leg:";
    private static final String TRANSFERRED_SOURCE_AGENT_KEY_PREFIX = "callnexus:call:transferred-source-agent:";
    private static final String TRANSFERRED_SOURCE_EXTENSION_KEY_PREFIX = "callnexus:call:transferred-source-extension:";
    private static final String TRANSFERRED_SOURCE_LEG_KEY_PREFIX = "callnexus:call:transferred-source-leg:";
    private static final String PHONE_MODE_WEBRTC = "WEBRTC";
    private static final String PHONE_MODE_EXTERNAL_SOFTPHONE = "EXTERNAL_SOFTPHONE";
    private static final Duration ACTIVE_CALL_TTL = Duration.ofHours(4);
    private static final Duration TRANSFERRED_SOURCE_TTL = Duration.ofMinutes(10);
    private static final long CONSULT_CANCEL_REBRIDGE_DELAY_MILLIS = 500L;
    private static final long BLIND_TRANSFER_MEDIA_RECOVERY_DELAY_MILLIS = 200L;

    private final CurrentAgentSessionService agentSessionService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway telephonyCommandGateway;
    private final OutboundAuthorizationService outboundAuthorizationService;
    private final AgentRealtimeQueryService agentRealtimeQueryService;
    private final CallLegMapper callLegMapper;
    private final CallEventMapper callEventMapper;
    private final CallSessionMapper callSessionMapper;

    @Override
    public CallControlResponse originate(String destination) {
        return originate(destination, CallOriginateContext.empty());
    }

    @Override
    public CallControlResponse originate(String destination, CallOriginateContext context) {
        CurrentAgentResponse agent = requireSignedInAgent();
        String key = activeCallKey(agent.getAgentId());
        AgentActiveCall existingCall = RedisUtils.getCacheObject(key);
        if (existingCall != null) {
            if (telephonyCommandGateway.callExists(endpoint(agent.getNodeId()), existingCall.getCallId())) {
                throw new ServiceException("当前坐席已有通话，请先处理当前通话");
            }
            RedisUtils.deleteObject(key);
        }

        String callId = UUID.randomUUID().toString();
        String businessCallId = context != null && context.businessCallId() != null && !context.businessCallId().isBlank()
            ? context.businessCallId() : callId;
        CallOriginateContext effectiveContext = normalizeContext(context, businessCallId);
        OutboundAuthorizationResult authorization = authorizeOutbound(agent, destination, effectiveContext);
        OutboundRoute outboundRoute = toOutboundRoute(authorization);
        String authorizedDestination = authorization.normalizedCallee();
        telephonyCommandGateway.originate(endpoint(agent.getNodeId()), callId, agent.getExtension(), authorizedDestination, outboundRoute,
            effectiveContext);

        AgentActiveCall activeCall = new AgentActiveCall();
        activeCall.setCallId(callId);
        activeCall.setBusinessCallId(businessCallId);
        activeCall.setAgentId(agent.getAgentId());
        activeCall.setAgentExtension(agent.getExtension());
        activeCall.setDestination(authorizedDestination);
        activeCall.setExternal(outboundRoute.isExternal());
        activeCall.setGatewayCode(outboundRoute.getGatewayCode());
        activeCall.setCallerIdNumber(outboundRoute.getCallerIdNumber());
        RedisUtils.setCacheObject(key, activeCall, ACTIVE_CALL_TTL);
        agentSessionService.changeStatus(AgentPresenceStatus.BUSY);
        return toResponse(activeCall);
    }

    @Override
    public void hangup(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        requireActiveCall(agent, callId);
        telephonyCommandGateway.hangup(endpoint(agent.getNodeId()), callId);
        RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
        agentSessionService.changeStatus(AgentPresenceStatus.AFTER_CALL);
    }

    @Override
    public void hold(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        requireActiveCall(agent, callId);
        telephonyCommandGateway.hold(endpoint(agent.getNodeId()), callId);
    }

    @Override
    public void unhold(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        requireActiveCall(agent, callId);
        telephonyCommandGateway.unhold(endpoint(agent.getNodeId()), callId);
    }

    @Override
    public void mute(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CurrentCallLegs legs = resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, callId);
        telephonyCommandGateway.mute(endpoint, legs.agentLegUuid());
        log.info("已静音当前坐席腿，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，sourceAgentLegUuid={}，agentId={}，extension={}，mute=true",
            LoginHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
            agent.getAgentId(), agent.getExtension());
    }

    @Override
    public void unmute(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CurrentCallLegs legs = resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, callId);
        telephonyCommandGateway.unmute(endpoint, legs.agentLegUuid());
        log.info("已取消静音当前坐席腿，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，sourceAgentLegUuid={}，agentId={}，extension={}，mute=false",
            LoginHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
            agent.getAgentId(), agent.getExtension());
    }

    @Override
    public void sendDtmf(String callId, String digits) {
        String safeDigits = normalizeDtmfDigits(digits);
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CurrentCallLegs legs = resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, callId);
        telephonyCommandGateway.sendDtmf(endpoint, legs.agentLegUuid(), safeDigits);
        log.info("已向当前坐席腿发送 DTMF，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，sourceAgentLegUuid={}，agentId={}，extension={}，digits={}",
            LoginHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
            agent.getAgentId(), agent.getExtension(), safeDigits);
    }

    @Override
    public void saveNote(String callId, String content) {
        String safeContent = normalizeNoteContent(content);
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CurrentCallLegs legs = resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, callId);
        CallSession session = callSessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .select(CallSession::getId)
            .eq(CallSession::getBusinessCallId, legs.businessCallId())
            .last("limit 1"));
        if (session == null) {
            throw new ServiceException("当前通话记录不存在，无法保存备注");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("content", safeContent);
        metadata.put("agentId", agent.getAgentId());
        metadata.put("agentExtension", agent.getExtension());
        metadata.put("userId", agent.getUserId());
        metadata.put("requestCallId", callId);
        metadata.put("businessCallId", legs.businessCallId());
        metadata.put("customerLegUuid", legs.customerLegUuid());
        metadata.put("sourceAgentLegUuid", legs.agentLegUuid());

        CallEvent event = new CallEvent();
        event.setSessionId(session.getId());
        event.setChannelUuid(legs.agentLegUuid());
        event.setRelatedChannelUuid(legs.customerLegUuid());
        event.setEventType("CALL_NOTE");
        event.setFromTarget(agent.getExtension());
        event.setToTarget("CALL_NOTE");
        event.setOccurredAt(LocalDateTime.now());
        event.setMetadataJson(JsonUtils.toJsonString(metadata));
        callEventMapper.insert(event);
        log.info("已保存通话备注，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，sourceAgentLegUuid={}，agentId={}，extension={}，contentLength={}",
            LoginHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
            agent.getAgentId(), agent.getExtension(), safeContent.length());
    }

    @Override
    public void blindTransfer(String callId, String targetExtension) {
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        String businessCallId = firstNotBlank(activeCall.getBusinessCallId(), resolveBusinessCallIdFromActiveCall(activeCall), activeCall.getCallId());
        String customerCallId = resolveCurrentCustomerLegId(endpoint, businessCallId, activeCall);
        if (customerCallId == null || customerCallId.isBlank() || !telephonyCommandGateway.callExists(endpoint, customerCallId)) {
            throw new ServiceException("当前客户通话腿不存在，无法盲转");
        }
        String customerRole = legRole(businessCallId, customerCallId);
        if (customerRole != null && !"CUSTOMER".equals(customerRole)) {
            throw new ServiceException("盲转目标通话腿不是客户腿，已拒绝执行");
        }
        if (!customerCallId.equals(callId)) {
            log.info("盲转请求传入的通话腿不是客户腿，已自动改用客户腿执行，requestCallId={}，customerCallId={}，businessCallId={}",
                callId, customerCallId, businessCallId);
        }
        prepareCustomerLegForBlindTransfer(endpoint, customerCallId);
        telephonyCommandGateway.blindTransfer(endpoint, customerCallId, targetExtension);
        notifyBlindTransferTargetAgent(agent, activeCall, targetExtension, businessCallId, customerCallId);
        RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
        RedisUtils.deleteObject(consultCallKey(agent.getAgentId()));
        agentSessionService.changeStatus(AgentPresenceStatus.AFTER_CALL);
    }

    @Override
    public CallControlResponse startConsultTransfer(String callId, String targetExtension, String phoneMode) {
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        AgentConsultCall existing = RedisUtils.getCacheObject(consultCallKey(agent.getAgentId()));
        if (existing != null) {
            if (telephonyCommandGateway.callExists(endpoint(agent.getNodeId()), existing.getConsultCallId())) {
                throw new ServiceException("当前坐席已有咨询通话，请先完成或取消咨询转接");
            }
            deleteConsultCall(agent, existing);
        }

        EslEndpoint endpoint = endpoint(agent.getNodeId());
        String consultCallId = UUID.randomUUID().toString();
        String businessCallId = firstNotBlank(activeCall.getBusinessCallId(), resolveBusinessCallIdFromActiveCall(activeCall), activeCall.getCallId());
        String customerCallId = resolveCurrentCustomerLegId(endpoint, businessCallId, activeCall);
        businessCallId = firstNotBlank(businessCallId, customerCallId);
        String sourceAgentCallId = resolveCurrentSourceAgentLegId(endpoint, businessCallId, agent, activeCall, customerCallId);
        validateConsultStartLegs(businessCallId, customerCallId, sourceAgentCallId);
        AgentConsultCall consultCall = new AgentConsultCall();
        consultCall.setOriginalCallId(customerCallId);
        consultCall.setBusinessCallId(businessCallId);
        consultCall.setConsultCallId(consultCallId);
        consultCall.setAgentChannelId(sourceAgentCallId);
        consultCall.setCustomerCallId(customerCallId);
        consultCall.setSourceAgentCallId(sourceAgentCallId);
        consultCall.setTargetAgentCallId(consultCallId);
        consultCall.setTenantId(LoginHelper.getTenantId());
        consultCall.setNodeId(agent.getNodeId());
        consultCall.setCustomerLegUuid(customerCallId);
        consultCall.setSourceAgentLegUuid(sourceAgentCallId);
        consultCall.setConsultLegUuid(consultCallId);
        consultCall.setStatus(AgentConsultCallStatus.CUSTOMER_HOLDING);
        consultCall.setAgentId(agent.getAgentId());
        consultCall.setAgentExtension(agent.getExtension());
        AgentRealtimeTargetResponse target = agentRealtimeQueryService.findByNodeAndExtension(agent.getNodeId(), targetExtension);
        if (target != null) {
            consultCall.setTargetAgentId(target.getAgentId());
        }
        consultCall.setTargetExtension(targetExtension);
        consultCall.setPhoneMode(normalizePhoneMode(phoneMode));
        consultCall.setStartedAt(LocalDateTime.now());
        try {
            prepareConsultBridge(endpoint, customerCallId, sourceAgentCallId);
            telephonyCommandGateway.hold(endpoint, customerCallId);
            parkSourceAgentChannelIfExists(endpoint, consultCall);
            waitForConsultBridgeReleased();
            saveConsultCall(agent, consultCall);
            telephonyCommandGateway.originateConsultation(endpoint, businessCallId, consultCallId, agent.getExtension(), targetExtension,
                customerCallId, sourceAgentCallId);
            consultCall.setStatus(AgentConsultCallStatus.DIALING);
            saveConsultCall(agent, consultCall);
        } catch (RuntimeException exception) {
            deleteConsultCall(agent, consultCall);
            restoreOriginalBridgeIfPossible(agent, consultCall);
            throw exception;
        }

        CallControlResponse response = new CallControlResponse();
        response.setCallId(consultCallId);
        response.setBusinessCallId(businessCallId);
        response.setAgentExtension(agent.getExtension());
        response.setDestination(targetExtension);
        response.setExternal(false);
        response.setStatus("CONSULT_DIALING");
        return response;
    }

    @Override
    public void cancelConsultTransfer(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentConsultCall consultCall = requireConsultCall(agent, callId);
        consultCall.setStatus(AgentConsultCallStatus.CANCELLING);
        saveConsultCall(agent, consultCall);
        requireActiveCall(agent, consultCall.getCustomerCallId());
        restoreOriginalBridgeIfPossible(agent, consultCall);
        waitForConsultBridgeReleased();
        hangupConsultCallIfExists(agent, consultCall);
        consultCall.setStatus(AgentConsultCallStatus.CANCELLED);
        deleteConsultCall(agent, consultCall);
    }

    @Override
    public void completeConsultTransfer(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentConsultCall consultCall = requireConsultCall(agent, callId);
        AgentActiveCall activeCall = requireActiveCall(agent, consultCall.getCustomerCallId());
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        validateConsultCompleteLegs(consultCall);
        if (!telephonyCommandGateway.callExists(endpoint, consultCall.getTargetAgentCallId())) {
            throw new ServiceException("目标咨询通话未接通或已结束，无法完成转接");
        }
        consultCall.setStatus(AgentConsultCallStatus.COMPLETING);
        saveConsultCall(agent, consultCall);
        if (isWebRtcConsult(consultCall)) {
            completeWebRtcConsultTransfer(agent, activeCall, consultCall, endpoint);
            return;
        }
        completeExternalSoftphoneConsultTransfer(agent, activeCall, consultCall, endpoint);
    }

    private void completeWebRtcConsultTransfer(CurrentAgentResponse agent, AgentActiveCall activeCall,
                                               AgentConsultCall consultCall, EslEndpoint endpoint) {
        prepareWebRtcTargetForRebridge(endpoint, consultCall);
        parkSourceAgentChannelIfExists(endpoint, consultCall);
        unholdIfPossible(endpoint, consultCall.getCustomerCallId());
        unholdIfPossible(endpoint, consultCall.getTargetAgentCallId());
        telephonyCommandGateway.bridgeCalls(endpoint, consultCall.getCustomerCallId(), consultCall.getTargetAgentCallId());
        recoverBridgeMedia(endpoint, consultCall.getCustomerCallId(), consultCall.getTargetAgentCallId());
        markCompletedTransferSource(agent, activeCall, consultCall);
        prepareCompletedTransferBridge(endpoint, consultCall);
        hangupSourceAgentChannelIfExists(endpoint, consultCall);
        finishConsultTransfer(agent, activeCall, consultCall);
    }

    private void completeExternalSoftphoneConsultTransfer(CurrentAgentResponse agent, AgentActiveCall activeCall,
                                                          AgentConsultCall consultCall, EslEndpoint endpoint) {
        prepareExternalSoftphoneTargetForRebridge(endpoint, consultCall);
        unholdIfPossible(endpoint, consultCall.getCustomerCallId());
        unholdIfPossible(endpoint, consultCall.getTargetAgentCallId());
        telephonyCommandGateway.bridgeCalls(endpoint, consultCall.getCustomerCallId(), consultCall.getTargetAgentCallId());
        unholdIfPossible(endpoint, consultCall.getCustomerCallId());
        unholdIfPossible(endpoint, consultCall.getTargetAgentCallId());
        recoverBridgeMedia(endpoint, consultCall.getCustomerCallId(), consultCall.getTargetAgentCallId());
        markCompletedTransferSource(agent, activeCall, consultCall);
        prepareCompletedTransferBridge(endpoint, consultCall);
        hangupSourceAgentChannelIfExists(endpoint, consultCall);
        finishConsultTransfer(agent, activeCall, consultCall);
    }

    private void finishConsultTransfer(CurrentAgentResponse agent, AgentActiveCall activeCall, AgentConsultCall consultCall) {
        notifyConsultSourceTransferCompleted(agent, activeCall, consultCall);
        notifyTransferredTargetAgent(agent, activeCall, consultCall);
        consultCall.setStatus(AgentConsultCallStatus.COMPLETED);
        consultCall.setCompletedAt(LocalDateTime.now());
        RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
        deleteConsultCall(agent, consultCall);
        agentSessionService.changeStatus(AgentPresenceStatus.AFTER_CALL);
    }

    private void saveConsultCall(CurrentAgentResponse agent, AgentConsultCall consultCall) {
        RedisUtils.setCacheObject(consultCallKey(agent.getAgentId()), consultCall, ACTIVE_CALL_TTL);
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

    private void deleteConsultCall(CurrentAgentResponse agent, AgentConsultCall consultCall) {
        RedisUtils.deleteObject(consultCallKey(agent.getAgentId()));
        deleteConsultLegIndex(consultCall == null ? null : consultCall.getCustomerLegUuid());
        deleteConsultLegIndex(consultCall == null ? null : consultCall.getSourceAgentLegUuid());
        deleteConsultLegIndex(consultCall == null ? null : consultCall.getConsultLegUuid());
    }

    private void deleteConsultLegIndex(String legUuid) {
        if (legUuid == null || legUuid.isBlank()) {
            return;
        }
        RedisUtils.deleteObject(consultLegKey(legUuid));
    }

    private void markCompletedTransferSource(CurrentAgentResponse agent, AgentActiveCall activeCall, AgentConsultCall consultCall) {
        if (agent == null || agent.getAgentId() == null || consultCall == null) {
            return;
        }
        Set<String> relatedUuids = new LinkedHashSet<>();
        if (activeCall != null) {
            addCallId(relatedUuids, activeCall.getCallId());
            addCallId(relatedUuids, activeCall.getAgentChannelId());
            if (activeCall.getRelatedUuids() != null) {
                activeCall.getRelatedUuids().forEach(uuid -> addCallId(relatedUuids, uuid));
            }
        }
        addCallId(relatedUuids, consultCall.getOriginalCallId());
        addCallId(relatedUuids, consultCall.getCustomerCallId());
        addCallId(relatedUuids, consultCall.getSourceAgentCallId());
        addCallId(relatedUuids, consultCall.getTargetAgentCallId());
        addCallId(relatedUuids, consultCall.getCustomerLegUuid());
        addCallId(relatedUuids, consultCall.getSourceAgentLegUuid());
        addCallId(relatedUuids, consultCall.getConsultLegUuid());
        relatedUuids.forEach(uuid -> RedisUtils.setCacheObject(
            transferredSourceAgentKey(LoginHelper.getTenantId(), uuid, agent.getAgentId()),
            Boolean.TRUE,
            TRANSFERRED_SOURCE_TTL
        ));
        relatedUuids.forEach(uuid -> RedisUtils.setCacheObject(
            transferredSourceExtensionKey(LoginHelper.getTenantId(), uuid, agent.getExtension()),
            Boolean.TRUE,
            TRANSFERRED_SOURCE_TTL
        ));
        Set<String> sourceLegUuids = new LinkedHashSet<>();
        addCallId(sourceLegUuids, consultCall.getSourceAgentCallId());
        addCallId(sourceLegUuids, consultCall.getSourceAgentLegUuid());
        sourceLegUuids.forEach(uuid -> RedisUtils.setCacheObject(
            transferredSourceLegKey(uuid),
            Boolean.TRUE,
            TRANSFERRED_SOURCE_TTL
        ));
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

    private String normalizePhoneMode(String phoneMode) {
        return PHONE_MODE_WEBRTC.equalsIgnoreCase(phoneMode) ? PHONE_MODE_WEBRTC : PHONE_MODE_EXTERNAL_SOFTPHONE;
    }

    private boolean isWebRtcConsult(AgentConsultCall consultCall) {
        return consultCall != null && PHONE_MODE_WEBRTC.equalsIgnoreCase(consultCall.getPhoneMode());
    }

    private CurrentAgentResponse requireSignedInAgent() {
        CurrentAgentResponse agent = agentSessionService.current();
        if (!agent.isConfigured()) {
            throw new ServiceException("当前用户尚未绑定坐席");
        }
        if (agent.getStatus() == AgentPresenceStatus.OFFLINE) {
            throw new ServiceException("坐席未签入，请先签入");
        }
        if (agent.getNodeId() == null || agent.getExtension() == null || agent.getExtension().isBlank()) {
            throw new ServiceException("坐席未绑定 SIP 分机或分机已停用");
        }
        return agent;
    }

    private AgentActiveCall requireActiveCall(CurrentAgentResponse agent, String callId) {
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(agent.getAgentId()));
        if (activeCall == null || !matchesActiveCall(activeCall, callId)) {
            throw new ServiceException("当前通话不存在或已结束");
        }
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        if (liveCallId(endpoint, activeCall, callId) == null) {
            RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
            throw new ServiceException("当前通话已在 FreeSWITCH 侧结束");
        }
        return activeCall;
    }

    private boolean matchesActiveCall(AgentActiveCall activeCall, String callId) {
        if (activeCall == null || callId == null || callId.isBlank()) {
            return false;
        }
        if (callId.equals(activeCall.getCallId())) {
            return true;
        }
        return activeCall.getRelatedUuids() != null && activeCall.getRelatedUuids().contains(callId);
    }

    private String liveCallId(EslEndpoint endpoint, AgentActiveCall activeCall, String preferredCallId) {
        for (String callId : candidateCallIds(activeCall, preferredCallId)) {
            if (telephonyCommandGateway.callExists(endpoint, callId)) {
                return callId;
            }
        }
        return null;
    }

    private String liveOriginalCallId(EslEndpoint endpoint, AgentActiveCall activeCall) {
        if (activeCall == null) {
            return null;
        }
        if (activeCall.getCallId() != null && !activeCall.getCallId().isBlank()
            && telephonyCommandGateway.callExists(endpoint, activeCall.getCallId())) {
            return activeCall.getCallId();
        }
        if (activeCall.getRelatedUuids() == null) {
            return null;
        }
        return activeCall.getRelatedUuids().stream()
            .filter(this::isUuid)
            .filter(uuid -> telephonyCommandGateway.callExists(endpoint, uuid))
            .findFirst()
            .orElse(null);
    }

    private String resolveBusinessCallIdFromActiveCall(AgentActiveCall activeCall) {
        if (activeCall == null) {
            return null;
        }
        for (String callId : candidateCallIds(activeCall, activeCall.getCallId())) {
            CallLeg leg = callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
                .select(CallLeg::getBusinessCallId)
                .eq(CallLeg::getLegUuid, callId)
                .last("limit 1"));
            if (leg != null && leg.getBusinessCallId() != null && !leg.getBusinessCallId().isBlank()) {
                return leg.getBusinessCallId();
            }
        }
        return null;
    }

    private String resolveCurrentCustomerLegId(EslEndpoint endpoint, String businessCallId, AgentActiveCall activeCall) {
        String legUuid = liveLegUuid(endpoint, activeLegByRole(businessCallId, "CUSTOMER"));
        if (legUuid != null) {
            return legUuid;
        }
        return liveOriginalCallId(endpoint, activeCall);
    }

    private String resolveCurrentSourceAgentLegId(EslEndpoint endpoint, String businessCallId, CurrentAgentResponse agent,
                                                  AgentActiveCall activeCall, String customerCallId) {
        String legUuid = liveLegUuid(endpoint, activeAgentLeg(businessCallId, agent));
        if (legUuid != null && !legUuid.equals(customerCallId)) {
            return legUuid;
        }
        legUuid = resolveAgentChannelId(endpoint, activeCall, customerCallId);
        if (legUuid != null && legUuid.equals(customerCallId)) {
            throw new ServiceException("当前咨询转接源坐席腿识别异常，拒绝发起咨询");
        }
        return legUuid;
    }

    private CallLeg activeLegByRole(String businessCallId, String role) {
        if (businessCallId == null || businessCallId.isBlank()) {
            return null;
        }
        return callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getBusinessCallId, businessCallId)
            .eq(CallLeg::getLegRole, role)
            .eq(CallLeg::getActive, true)
            .last("order by create_time desc limit 1"));
    }

    private CallLeg activeAgentLeg(String businessCallId, CurrentAgentResponse agent) {
        if (businessCallId == null || businessCallId.isBlank() || agent == null || agent.getAgentId() == null) {
            return null;
        }
        return callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getBusinessCallId, businessCallId)
            .eq(CallLeg::getAgentId, agent.getAgentId())
            .eq(CallLeg::getActive, true)
            .in(CallLeg::getLegRole, List.of("AGENT", "CONSULT_AGENT"))
            .last("order by create_time desc limit 1"));
    }

    private String liveLegUuid(EslEndpoint endpoint, CallLeg leg) {
        if (leg == null || leg.getLegUuid() == null || leg.getLegUuid().isBlank()) {
            return null;
        }
        return telephonyCommandGateway.callExists(endpoint, leg.getLegUuid()) ? leg.getLegUuid() : null;
    }

    private void validateConsultStartLegs(String businessCallId, String customerCallId, String sourceAgentCallId) {
        if (!isUuid(customerCallId) || !isUuid(sourceAgentCallId)) {
            throw new ServiceException("当前通话三腿信息不完整，无法发起咨询转接");
        }
        if (customerCallId.equals(sourceAgentCallId)) {
            throw new ServiceException("当前咨询转接客户腿和源坐席腿相同，拒绝发起咨询");
        }
        String customerRole = legRole(businessCallId, customerCallId);
        String sourceRole = legRole(businessCallId, sourceAgentCallId);
        if (customerRole != null && !"CUSTOMER".equals(customerRole)) {
            throw new ServiceException("当前咨询转接客户腿识别异常，拒绝发起咨询");
        }
        if ("CUSTOMER".equals(sourceRole)) {
            throw new ServiceException("当前咨询转接源坐席腿识别异常，拒绝发起咨询");
        }
    }

    private void validateConsultCompleteLegs(AgentConsultCall consultCall) {
        if (consultCall == null
            || !isUuid(consultCall.getCustomerCallId())
            || !isUuid(consultCall.getSourceAgentCallId())
            || !isUuid(consultCall.getTargetAgentCallId())) {
            throw new ServiceException("咨询转接三腿信息不完整，无法完成转接");
        }
        if (consultCall.getCustomerCallId().equals(consultCall.getSourceAgentCallId())
            || consultCall.getCustomerCallId().equals(consultCall.getTargetAgentCallId())
            || consultCall.getSourceAgentCallId().equals(consultCall.getTargetAgentCallId())) {
            throw new ServiceException("咨询转接三腿信息异常，拒绝执行错误桥接");
        }
        String customerRole = legRole(consultCall.getBusinessCallId(), consultCall.getCustomerCallId());
        String sourceRole = legRole(consultCall.getBusinessCallId(), consultCall.getSourceAgentCallId());
        String targetRole = legRole(consultCall.getBusinessCallId(), consultCall.getTargetAgentCallId());
        if (customerRole != null && !"CUSTOMER".equals(customerRole)) {
            throw new ServiceException("咨询转接客户腿不是客户通道，拒绝完成转接");
        }
        if ("CUSTOMER".equals(sourceRole)) {
            throw new ServiceException("咨询转接源坐席腿识别为客户通道，拒绝完成转接");
        }
        if (targetRole != null && !"CONSULT_AGENT".equals(targetRole) && !"AGENT".equals(targetRole)) {
            throw new ServiceException("咨询转接目标腿不是坐席通道，拒绝完成转接");
        }
    }

    private String legRole(String businessCallId, String legUuid) {
        if (businessCallId == null || businessCallId.isBlank() || legUuid == null || legUuid.isBlank()) {
            return null;
        }
        CallLeg leg = callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .select(CallLeg::getLegRole)
            .eq(CallLeg::getBusinessCallId, businessCallId)
            .eq(CallLeg::getLegUuid, legUuid)
            .last("limit 1"));
        return leg == null ? null : leg.getLegRole();
    }

    private Set<String> candidateCallIds(AgentActiveCall activeCall, String preferredCallId) {
        Set<String> callIds = new LinkedHashSet<>();
        addCallId(callIds, preferredCallId);
        if (activeCall != null) {
            addCallId(callIds, activeCall.getCallId());
            if (activeCall.getRelatedUuids() != null) {
                activeCall.getRelatedUuids().forEach(uuid -> addCallId(callIds, uuid));
            }
        }
        return callIds;
    }

    private void addCallId(Set<String> callIds, String callId) {
        if (callId != null && !callId.isBlank()) {
            callIds.add(callId);
        }
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }

    private String activeCallKey(Long agentId) {
        return ACTIVE_CALL_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agentId;
    }

    private String activeCallKey(String tenantId, Long agentId) {
        return ACTIVE_CALL_KEY_PREFIX + tenantId + ":" + agentId;
    }

    private String consultCallKey(Long agentId) {
        return CONSULT_CALL_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agentId;
    }

    private String consultLegKey(String legUuid) {
        return CONSULT_LEG_KEY_PREFIX + legUuid;
    }

    private AgentConsultCall requireConsultCall(CurrentAgentResponse agent, String callId) {
        AgentConsultCall consultCall = RedisUtils.getCacheObject(consultCallKey(agent.getAgentId()));
        if (consultCall == null) {
            consultCall = RedisUtils.getCacheObject(consultLegKey(callId));
        }
        if (consultCall == null
            || !agent.getAgentId().equals(consultCall.getAgentId())
            || !matchesConsultCall(consultCall, callId)) {
            throw new ServiceException("当前没有进行中的咨询转接");
        }
        return consultCall;
    }

    private boolean matchesConsultCall(AgentConsultCall consultCall, String callId) {
        if (consultCall == null || callId == null || callId.isBlank()) {
            return false;
        }
        return callId.equals(consultCall.getCustomerCallId())
            || callId.equals(consultCall.getSourceAgentCallId())
            || callId.equals(consultCall.getTargetAgentCallId())
            || callId.equals(consultCall.getCustomerLegUuid())
            || callId.equals(consultCall.getSourceAgentLegUuid())
            || callId.equals(consultCall.getConsultLegUuid())
            || callId.equals(consultCall.getConsultCallId())
            || callId.equals(consultCall.getOriginalCallId());
    }

    private void hangupConsultCallIfExists(CurrentAgentResponse agent, AgentConsultCall consultCall) {
        if (consultCall.getTargetAgentCallId() == null || consultCall.getTargetAgentCallId().isBlank()) {
            return;
        }
        if (telephonyCommandGateway.callExists(endpoint(agent.getNodeId()), consultCall.getTargetAgentCallId())) {
            telephonyCommandGateway.hangup(endpoint(agent.getNodeId()), consultCall.getTargetAgentCallId());
        }
    }

    private void prepareConsultBridge(EslEndpoint endpoint, String originalCallId, String agentChannelId) {
        telephonyCommandGateway.setCallVariable(endpoint, originalCallId, "hangup_after_bridge", "false");
        telephonyCommandGateway.setCallVariable(endpoint, originalCallId, "park_after_bridge", "true");
        telephonyCommandGateway.setCallVariable(endpoint, agentChannelId, "hangup_after_bridge", "false");
        telephonyCommandGateway.setCallVariable(endpoint, agentChannelId, "park_after_bridge", "true");
    }

    private void prepareCompletedTransferBridge(EslEndpoint endpoint, AgentConsultCall consultCall) {
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getCustomerCallId(), "hangup_after_bridge", "true");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getCustomerCallId(), "park_after_bridge", "false");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getTargetAgentCallId(), "hangup_after_bridge", "true");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getTargetAgentCallId(), "park_after_bridge", "false");
    }

    private void prepareWebRtcTargetForRebridge(EslEndpoint endpoint, AgentConsultCall consultCall) {
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getCustomerCallId(), "hangup_after_bridge", "false");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getCustomerCallId(), "park_after_bridge", "false");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getTargetAgentCallId(), "hangup_after_bridge", "false");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getTargetAgentCallId(), "park_after_bridge", "false");
    }

    private void prepareExternalSoftphoneTargetForRebridge(EslEndpoint endpoint, AgentConsultCall consultCall) {
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getCustomerCallId(), "hangup_after_bridge", "false");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getCustomerCallId(), "park_after_bridge", "false");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getTargetAgentCallId(), "hangup_after_bridge", "false");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getTargetAgentCallId(), "park_after_bridge", "false");
    }

    private void restoreOriginalBridgeIfPossible(CurrentAgentResponse agent, AgentConsultCall consultCall) {
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        prepareRestoredOriginalBridge(endpoint, consultCall);
        unholdIfPossible(endpoint, consultCall.getCustomerCallId());
        unholdSourceAgentIfPossible(endpoint, consultCall);
        if (consultCall.getSourceAgentCallId() != null && telephonyCommandGateway.callExists(endpoint, consultCall.getSourceAgentCallId())
            && telephonyCommandGateway.callExists(endpoint, consultCall.getCustomerCallId())) {
            telephonyCommandGateway.bridgeCalls(endpoint, consultCall.getSourceAgentCallId(), consultCall.getCustomerCallId());
            unholdIfPossible(endpoint, consultCall.getCustomerCallId());
            unholdSourceAgentIfPossible(endpoint, consultCall);
            recoverBridgeMedia(endpoint, consultCall.getSourceAgentCallId(), consultCall.getCustomerCallId());
        }
    }

    private void waitForConsultBridgeReleased() {
        try {
            Thread.sleep(CONSULT_CANCEL_REBRIDGE_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void prepareRestoredOriginalBridge(EslEndpoint endpoint, AgentConsultCall consultCall) {
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getCustomerCallId(), "hangup_after_bridge", "true");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getCustomerCallId(), "park_after_bridge", "false");
        if (consultCall.getSourceAgentCallId() == null
            || consultCall.getSourceAgentCallId().equals(consultCall.getCustomerCallId())
            || !telephonyCommandGateway.callExists(endpoint, consultCall.getSourceAgentCallId())) {
            return;
        }
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getSourceAgentCallId(), "hangup_after_bridge", "true");
        telephonyCommandGateway.setCallVariable(endpoint, consultCall.getSourceAgentCallId(), "park_after_bridge", "false");
    }

    private void prepareCustomerLegForBlindTransfer(EslEndpoint endpoint, String customerCallId) {
        telephonyCommandGateway.setCallVariable(endpoint, customerCallId, "hangup_after_bridge", "true");
        telephonyCommandGateway.setCallVariable(endpoint, customerCallId, "park_after_bridge", "false");
        unholdIfPossible(endpoint, customerCallId);
        recoverMediaIfPossible(endpoint, customerCallId);
        waitForBlindTransferMediaRecovery();
    }

    private void waitForBlindTransferMediaRecovery() {
        try {
            Thread.sleep(BLIND_TRANSFER_MEDIA_RECOVERY_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void unholdIfPossible(EslEndpoint endpoint, String callId) {
        try {
            telephonyCommandGateway.unhold(endpoint, callId);
        } catch (RuntimeException exception) {
            log.warn("取消保持通话失败，继续执行后续转接流程，callId={}，error={}", callId, exception.getMessage());
        }
    }

    private void recoverBridgeMedia(EslEndpoint endpoint, String leftCallId, String rightCallId) {
        recoverMediaIfPossible(endpoint, leftCallId);
        recoverMediaIfPossible(endpoint, rightCallId);
    }

    private void recoverMediaIfPossible(EslEndpoint endpoint, String callId) {
        try {
            telephonyCommandGateway.recoverMedia(endpoint, callId);
        } catch (RuntimeException exception) {
            log.warn("恢复桥接媒体失败，继续执行后续转接流程，callId={}，error={}", callId, exception.getMessage());
        }
    }

    private void unholdSourceAgentIfPossible(EslEndpoint endpoint, AgentConsultCall consultCall) {
        if (consultCall.getSourceAgentCallId() == null
            || consultCall.getSourceAgentCallId().equals(consultCall.getCustomerCallId())
            || consultCall.getSourceAgentCallId().equals(consultCall.getTargetAgentCallId())) {
            return;
        }
        unholdIfPossible(endpoint, consultCall.getSourceAgentCallId());
    }

    private void hangupSourceAgentChannelIfExists(EslEndpoint endpoint, AgentConsultCall consultCall) {
        if (consultCall.getSourceAgentCallId() == null
            || consultCall.getSourceAgentCallId().equals(consultCall.getCustomerCallId())
            || consultCall.getSourceAgentCallId().equals(consultCall.getTargetAgentCallId())) {
            return;
        }
        if (telephonyCommandGateway.callExists(endpoint, consultCall.getSourceAgentCallId())) {
            telephonyCommandGateway.hangup(endpoint, consultCall.getSourceAgentCallId());
        }
    }

    private void parkSourceAgentChannelIfExists(EslEndpoint endpoint, AgentConsultCall consultCall) {
        if (consultCall.getSourceAgentCallId() == null
            || consultCall.getSourceAgentCallId().equals(consultCall.getCustomerCallId())
            || consultCall.getSourceAgentCallId().equals(consultCall.getTargetAgentCallId())) {
            return;
        }
        if (telephonyCommandGateway.callExists(endpoint, consultCall.getSourceAgentCallId())) {
            telephonyCommandGateway.park(endpoint, consultCall.getSourceAgentCallId());
        }
    }

    private CurrentCallLegs resolveCurrentCallLegsForAgentControl(EslEndpoint endpoint, CurrentAgentResponse agent,
                                                                  AgentActiveCall activeCall, String requestCallId) {
        String businessCallId = firstNotBlank(activeCall.getBusinessCallId(), resolveBusinessCallIdFromActiveCall(activeCall), activeCall.getCallId());
        String customerLegUuid = resolveCurrentCustomerLegId(endpoint, businessCallId, activeCall);
        if (customerLegUuid == null || customerLegUuid.isBlank() || !telephonyCommandGateway.callExists(endpoint, customerLegUuid)) {
            throw new ServiceException("当前客户通话腿不存在，无法执行坐席控制动作");
        }
        String customerRole = legRole(businessCallId, customerLegUuid);
        if (customerRole != null && !"CUSTOMER".equals(customerRole)) {
            throw new ServiceException("当前业务通话的客户腿识别异常，已拒绝执行坐席控制动作");
        }
        String agentLegUuid = resolveCurrentSourceAgentLegId(endpoint, businessCallId, agent, activeCall, customerLegUuid);
        if (agentLegUuid == null || agentLegUuid.isBlank() || !telephonyCommandGateway.callExists(endpoint, agentLegUuid)) {
            throw new ServiceException("当前坐席通话腿不存在，无法执行坐席控制动作");
        }
        String agentRole = legRole(businessCallId, agentLegUuid);
        if ("CUSTOMER".equals(agentRole)) {
            throw new ServiceException("当前控制目标不是坐席腿，已拒绝执行");
        }
        if (!agentLegUuid.equals(requestCallId)) {
            log.info("坐席控制请求传入的 callId 不是当前坐席腿，已自动使用当前坐席腿执行，requestCallId={}，sourceAgentLegUuid={}，customerLegUuid={}，businessCallId={}",
                requestCallId, agentLegUuid, customerLegUuid, businessCallId);
        }
        return new CurrentCallLegs(businessCallId, customerLegUuid, agentLegUuid);
    }

    private String normalizeDtmfDigits(String digits) {
        if (digits == null) {
            throw new ServiceException("DTMF 按键不能为空");
        }
        String normalized = digits.trim().toUpperCase();
        if (!normalized.matches("^[0-9A-D*#]{1,32}$")) {
            throw new ServiceException("DTMF 按键只能包含 0-9、*、#、A-D，最多 32 位");
        }
        return normalized;
    }

    private String normalizeNoteContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new ServiceException("通话备注不能为空");
        }
        String normalized = content.trim();
        if (normalized.length() > 1000) {
            throw new ServiceException("通话备注不能超过 1000 个字符");
        }
        return normalized;
    }

    private String resolveAgentChannelId(EslEndpoint endpoint, AgentActiveCall activeCall, String customerCallId) {
        if (activeCall.getRelatedUuids() == null || activeCall.getRelatedUuids().isEmpty()) {
            throw new ServiceException("当前通话缺少坐席通话腿，无法发起咨询转接");
        }
        return activeCall.getRelatedUuids().stream()
            .filter(this::isUuid)
            .filter(uuid -> !customerCallId.equals(uuid))
            .filter(uuid -> telephonyCommandGateway.callExists(endpoint, uuid))
            .findFirst()
            .orElseThrow(() -> new ServiceException("当前坐席通话腿已不存在，无法发起咨询转接"));
    }

    private boolean isUuid(String value) {
        return value != null && value.matches("^[0-9a-fA-F-]{36}$");
    }

    private void notifyTransferredTargetAgent(CurrentAgentResponse sourceAgent, AgentActiveCall sourceCall, String targetExtension) {
        AgentConsultCall syntheticConsultCall = new AgentConsultCall();
        syntheticConsultCall.setTargetExtension(targetExtension);
        notifyTransferredTargetAgent(sourceAgent, sourceCall, syntheticConsultCall);
    }

    private void notifyBlindTransferTargetAgent(CurrentAgentResponse sourceAgent, AgentActiveCall sourceCall, String targetExtension,
                                                String businessCallId, String customerCallId) {
        AgentConsultCall syntheticConsultCall = new AgentConsultCall();
        syntheticConsultCall.setTargetExtension(targetExtension);
        syntheticConsultCall.setBusinessCallId(businessCallId);
        syntheticConsultCall.setCustomerCallId(customerCallId);
        syntheticConsultCall.setCustomerLegUuid(customerCallId);
        notifyTransferredTargetAgent(sourceAgent, sourceCall, syntheticConsultCall);
    }

    private void notifyConsultSourceTransferCompleted(CurrentAgentResponse sourceAgent, AgentActiveCall sourceCall, AgentConsultCall consultCall) {
        if (sourceAgent == null || sourceAgent.getUserId() == null || sourceCall == null) {
            return;
        }
        Set<String> finishedCallIds = new LinkedHashSet<>();
        addCallId(finishedCallIds, sourceCall.getCallId());
        addCallId(finishedCallIds, consultCall == null ? null : consultCall.getOriginalCallId());
        addCallId(finishedCallIds, consultCall == null ? null : consultCall.getCustomerCallId());
        addCallId(finishedCallIds, consultCall == null ? null : consultCall.getSourceAgentCallId());
        addCallId(finishedCallIds, consultCall == null ? null : consultCall.getCustomerLegUuid());
        addCallId(finishedCallIds, consultCall == null ? null : consultCall.getSourceAgentLegUuid());
        finishedCallIds.forEach(callId -> publishConsultSourceTransferCompleted(sourceAgent, sourceCall, callId));
    }

    private void publishConsultSourceTransferCompleted(CurrentAgentResponse sourceAgent, AgentActiveCall sourceCall, String callId) {
        CallRealtimeMessage message = new CallRealtimeMessage();
        message.setType("CALL_HANGUP_COMPLETE");
        message.setCallId(callId);
        message.setCallerNumber(sourceCall.getDestination());
        message.setCalledNumber(sourceAgent.getExtension());
        message.setAgentExtension(sourceAgent.getExtension());
        message.setHangupCause("CONSULT_TRANSFER_COMPLETED");
        message.setOccurredAt(LocalDateTime.now());
        publishRealtimeMessage(sourceAgent.getUserId(), JsonUtils.toJsonString(message));
    }

    private void notifyTransferredTargetAgent(CurrentAgentResponse sourceAgent, AgentActiveCall sourceCall, AgentConsultCall consultCall) {
        AgentRealtimeTargetResponse target = agentRealtimeQueryService.findByNodeAndExtension(sourceAgent.getNodeId(), consultCall.getTargetExtension());
        if (target == null || target.getUserId() == null) {
            return;
        }
        AgentActiveCall targetCall = new AgentActiveCall();
        targetCall.setCallId(consultCall.getCustomerCallId() == null ? sourceCall.getCallId() : consultCall.getCustomerCallId());
        targetCall.setBusinessCallId(firstNotBlank(consultCall.getBusinessCallId(), sourceCall.getBusinessCallId(), sourceCall.getCallId()));
        targetCall.setAgentChannelId(consultCall.getTargetAgentCallId());
        targetCall.setAgentId(target.getAgentId());
        targetCall.setAgentExtension(target.getExtension());
        targetCall.setDestination(sourceCall.getDestination());
        targetCall.setExternal(sourceCall.getExternal());
        targetCall.setGatewayCode(sourceCall.getGatewayCode());
        targetCall.setCallerIdNumber(sourceCall.getCallerIdNumber());
        Set<String> relatedUuids = new LinkedHashSet<>();
        addCallId(relatedUuids, consultCall.getCustomerCallId());
        addCallId(relatedUuids, consultCall.getTargetAgentCallId());
        if (consultCall.getCustomerCallId() == null) {
            addCallId(relatedUuids, sourceCall.getCallId());
        }
        targetCall.setRelatedUuids(relatedUuids);
        RedisUtils.setCacheObject(activeCallKey(target.getTenantId(), target.getAgentId()), targetCall, ACTIVE_CALL_TTL);

        CallRealtimeMessage message = new CallRealtimeMessage();
        message.setType("CALL_BRIDGE");
        message.setCallId(sourceCall.getCallId());
        message.setCallerNumber(sourceCall.getDestination());
        message.setCalledNumber(target.getExtension());
        message.setAgentExtension(target.getExtension());
        message.setOccurredAt(LocalDateTime.now());
        publishRealtimeMessage(target.getUserId(), JsonUtils.toJsonString(message));
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

    private OutboundAuthorizationResult authorizeOutbound(CurrentAgentResponse agent, String destination, CallOriginateContext context) {
        OutboundAuthorizationResult result = outboundAuthorizationService.authorize(new OutboundAuthorizationCommand(
            LoginHelper.getTenantId(),
            "AGENT_ORIGINATE",
            agent.getNodeId(),
            agent.getSipDomain(),
            null,
            agent.getAgentId(),
            LoginHelper.getUserId(),
            agent.getExtension(),
            destination,
            context != null && context.callerNumberId() != null ? context.callerNumberId() : agent.getCallerNumberId(),
            context == null ? null : context.outboundTaskId(),
            context == null ? null : context.outboundMemberId(),
            context == null ? null : context.customerId()
        ));
        if (!result.allowed()) {
            throw new ServiceException(result.rejectMessage());
        }
        return result;
    }

    private OutboundRoute toOutboundRoute(OutboundAuthorizationResult authorization) {
        if (!authorization.external()) {
            return OutboundRoute.internal();
        }
        return OutboundRoute.external(authorization.outboundRoute().getGatewayCode(), authorization.outboundRoute().getNumber());
    }

    private CallControlResponse toResponse(AgentActiveCall call) {
        CallControlResponse response = new CallControlResponse();
        response.setCallId(call.getCallId());
        response.setBusinessCallId(call.getBusinessCallId());
        response.setAgentExtension(call.getAgentExtension());
        response.setDestination(call.getDestination());
        response.setExternal(call.getExternal());
        response.setGatewayCode(call.getGatewayCode());
        response.setCallerIdNumber(call.getCallerIdNumber());
        response.setStatus("DIALING");
        return response;
    }

    private CallOriginateContext normalizeContext(CallOriginateContext context, String businessCallId) {
        if (context == null) {
            return new CallOriginateContext(businessCallId, null, null, null, null);
        }
        return new CallOriginateContext(
            businessCallId,
            context.customerId(),
            context.outboundTaskId(),
            context.outboundMemberId(),
            context.callerNumberId()
        );
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record CurrentCallLegs(String businessCallId, String customerLegUuid, String agentLegUuid) {
    }
}
