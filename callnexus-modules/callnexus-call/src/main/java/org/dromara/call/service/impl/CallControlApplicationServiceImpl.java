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
import org.dromara.agent.service.AgentSessionApplicationService;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.ai.service.AiRealtimeTelephonyGateway;
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
import org.dromara.call.service.CallConferenceApplicationService;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.call.service.DispatchCallTaskService;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
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
    private static final long CONSULT_BRIDGE_CONFIRM_TIMEOUT_MILLIS = 2_000L;
    private static final long CONSULT_BRIDGE_CONFIRM_INTERVAL_MILLIS = 100L;
    private static final long BLIND_TRANSFER_MEDIA_RECOVERY_DELAY_MILLIS = 200L;

    private final CurrentAgentSessionService agentSessionService;
    private final AgentSessionApplicationService explicitAgentSessionService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway telephonyCommandGateway;
    private final OutboundAuthorizationService outboundAuthorizationService;
    private final AgentRealtimeQueryService agentRealtimeQueryService;
    private final CallLegMapper callLegMapper;
    private final CallEventMapper callEventMapper;
    private final CallSessionMapper callSessionMapper;
    private final DispatchCallTaskService dispatchCallTaskService;
    private final CallConferenceApplicationService callConferenceApplicationService;
    private final AiRealtimeTelephonyGateway aiRealtimeTelephonyGateway;

    @Override
    public CallControlResponse originate(String destination) {
        return originate(destination, CallOriginateContext.empty());
    }

    @Override
    public CallControlResponse originate(String destination, CallOriginateContext context) {
        return originate(null, destination, context);
    }

    @Override
    public CallControlResponse originate(Long agentId, String destination, CallOriginateContext context) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
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
        try {
            changeAgentStatus(agent, AgentPresenceStatus.BUSY);
            telephonyCommandGateway.originate(endpoint(agent.getNodeId()), callId, agent.getExtension(),
                authorizedDestination, outboundRoute, effectiveContext);
        } catch (RuntimeException exception) {
            RedisUtils.deleteObject(key);
            restoreAgentStatusAfterOriginateFailure(agent);
            throw exception;
        }
        return toResponse(activeCall);
    }

    @Override
    public void hangup(String callId) {
        hangup(null, callId);
    }

    @Override
    public void hangup(Long agentId, String callId) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
        if (callConferenceApplicationService.endIfActiveOwner(agent.getAgentId(), callId)) {
            return;
        }
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        String hangupTarget = callId;
        try {
            if (!dispatchCallTaskService.terminateByOperatorLeg(callId)) {
                hangupTarget = resolveAgentHangupTarget(endpoint, agent, activeCall, callId);
                telephonyCommandGateway.hangup(endpoint, hangupTarget);
                log.info("坐席挂机已作用于当前坐席腿，requestCallId={}，agentLegUuid={}，agentId={}，extension={}",
                    callId, hangupTarget, agent.getAgentId(), agent.getExtension());
            }
        } catch (Exception exception) {
            if (telephonyCommandGateway.callExists(endpoint, hangupTarget)) {
                throw exception instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new ServiceException("挂机失败：" + exception.getMessage());
            }
            log.info("挂机时电话腿已在 FreeSWITCH 侧结束，按幂等成功处理，requestCallId={}，hangupTarget={}，agentId={}，extension={}",
                callId, hangupTarget, agent.getAgentId(), agent.getExtension());
        }
        RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
        changeAgentStatus(agent, AgentPresenceStatus.AFTER_CALL);
    }

    private String resolveAgentHangupTarget(EslEndpoint endpoint, CurrentAgentResponse agent,
                                            AgentActiveCall activeCall, String requestCallId) {
        try {
            return resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, requestCallId).agentLegUuid();
        } catch (ServiceException exception) {
            log.info("当前通话尚未形成完整客户腿与坐席腿，挂机沿用请求电话腿，requestCallId={}，agentId={}，extension={}，reason={}",
                requestCallId, agent.getAgentId(), agent.getExtension(), exception.getMessage());
            return requestCallId;
        }
    }

    @Override
    public void hold(String callId) {
        hold(null, callId);
    }

    @Override
    public void hold(Long agentId, String callId) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CurrentCallLegs legs = resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, callId);
        telephonyCommandGateway.hold(endpoint, legs.customerLegUuid());
        log.info("已保持当前客户腿，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，sourceAgentLegUuid={}，agentId={}，extension={}",
            TenantHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
            agent.getAgentId(), agent.getExtension());
    }

    @Override
    public void unhold(String callId) {
        unhold(null, callId);
    }

    @Override
    public void unhold(Long agentId, String callId) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CurrentCallLegs legs = resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, callId);
        telephonyCommandGateway.unhold(endpoint, legs.customerLegUuid());
        log.info("已恢复当前客户腿，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，sourceAgentLegUuid={}，agentId={}，extension={}",
            TenantHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
            agent.getAgentId(), agent.getExtension());
    }

    @Override
    public void mute(String callId) {
        mute(null, callId);
    }

    @Override
    public void mute(Long agentId, String callId) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CurrentCallLegs legs = resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, callId);
        telephonyCommandGateway.mute(endpoint, legs.agentLegUuid());
        log.info("已静音当前坐席腿，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，sourceAgentLegUuid={}，agentId={}，extension={}，mute=true",
            TenantHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
            agent.getAgentId(), agent.getExtension());
    }

    @Override
    public void unmute(String callId) {
        unmute(null, callId);
    }

    @Override
    public void unmute(Long agentId, String callId) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CurrentCallLegs legs = resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, callId);
        telephonyCommandGateway.unmute(endpoint, legs.agentLegUuid());
        log.info("已取消静音当前坐席腿，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，sourceAgentLegUuid={}，agentId={}，extension={}，mute=false",
            TenantHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
            agent.getAgentId(), agent.getExtension());
    }

    @Override
    public void sendDtmf(String callId, String digits) {
        sendDtmf(null, callId, digits);
    }

    @Override
    public void sendDtmf(Long agentId, String callId, String digits) {
        String safeDigits = normalizeDtmfDigits(digits);
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CurrentCallLegs legs = resolveCurrentCallLegsForAgentControl(endpoint, agent, activeCall, callId);
        telephonyCommandGateway.sendDtmf(endpoint, legs.agentLegUuid(), safeDigits);
        log.info("已向当前坐席腿发送 DTMF，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，sourceAgentLegUuid={}，agentId={}，extension={}，digits={}",
            TenantHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
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
            TenantHelper.getTenantId(), agent.getNodeId(), legs.businessCallId(), callId, legs.customerLegUuid(), legs.agentLegUuid(),
            agent.getAgentId(), agent.getExtension(), safeContent.length());
    }

    @Override
    public void blindTransfer(String callId, String targetExtension) {
        blindTransfer(null, callId, targetExtension);
    }

    @Override
    public void blindTransfer(Long agentId, String callId, String targetExtension) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        String businessCallId = resolveAuthoritativeBusinessCallId(activeCall);
        String customerCallId = resolveCurrentCustomerLegId(endpoint, businessCallId, activeCall, agent);
        if (customerCallId == null || customerCallId.isBlank() || !telephonyCommandGateway.callExists(endpoint, customerCallId)) {
            throw new ServiceException("当前客户通话腿不存在，无法盲转");
        }
        businessCallId = firstNotBlank(resolveBusinessCallIdByLegUuid(customerCallId), businessCallId, customerCallId);
        String customerRole = legRole(businessCallId, customerCallId);
        if (customerRole != null && !isAllowedCounterpartyRole(businessCallId, customerRole)) {
            throw new ServiceException("盲转目标通话腿不是客户腿，已拒绝执行");
        }
        if (!customerCallId.equals(callId)) {
            log.info("盲转请求传入的通话腿不是客户腿，已自动改用客户腿执行，requestCallId={}，customerCallId={}，businessCallId={}",
                callId, customerCallId, businessCallId);
        }
        prepareCustomerLegForBlindTransfer(endpoint, customerCallId);
        telephonyCommandGateway.setCallVariable(endpoint, customerCallId, "callnexus_satisfaction_skip", "true");
        telephonyCommandGateway.setCallVariable(endpoint, customerCallId, "callnexus_business_call_id", businessCallId);
        telephonyCommandGateway.setCallVariable(endpoint, customerCallId, "callnexus_original_caller",
            firstNotBlank(activeCall.getDestination(), activeCall.getCallerIdNumber(), agent.getExtension()));
        telephonyCommandGateway.setCallVariable(endpoint, customerCallId, "callnexus_original_called", targetExtension);
        markBlindTransferSource(agent, activeCall);
        telephonyCommandGateway.blindTransfer(endpoint, customerCallId, targetExtension);
        RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
        RedisUtils.deleteObject(consultCallKey(agent.getAgentId()));
        changeAgentStatus(agent, AgentPresenceStatus.AFTER_CALL);
    }

    @Override
    public void transferToIvr(String callId, Long flowId) {
        transferToIvr(null, callId, flowId);
    }

    @Override
    public void transferToIvr(Long agentId, String callId, Long flowId) {
        if (flowId == null) {
            throw new ServiceException("请选择 IVR 流程");
        }
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        String businessCallId = resolveAuthoritativeBusinessCallId(activeCall);
        String customerCallId = resolveCurrentCustomerLegId(endpoint, businessCallId, activeCall, agent);
        if (customerCallId == null || customerCallId.isBlank()
            || !telephonyCommandGateway.callExists(endpoint, customerCallId)) {
            throw new ServiceException("当前客户通话腿不存在，无法转入 IVR");
        }
        businessCallId = firstNotBlank(resolveBusinessCallIdByLegUuid(customerCallId), businessCallId, customerCallId);
        String customerRole = legRole(businessCallId, customerCallId);
        if (customerRole != null && !isAllowedCounterpartyRole(businessCallId, customerRole)) {
            throw new ServiceException("当前目标通话腿不是客户腿，已拒绝转入 IVR");
        }

        telephonyCommandGateway.setCallVariable(endpoint, customerCallId,
            "callnexus_business_call_id", businessCallId);
        aiRealtimeTelephonyGateway.transferToIvr(TenantHelper.getTenantId(), agent.getNodeId(),
            customerCallId, flowId.toString());

        RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
        RedisUtils.deleteObject(consultCallKey(agent.getAgentId()));
        changeAgentStatus(agent, AgentPresenceStatus.AFTER_CALL);
        log.info("坐席已将客户通话转入 IVR，tenantId={}，nodeId={}，businessCallId={}，requestCallId={}，customerLegUuid={}，agentId={}，extension={}，flowId={}",
            TenantHelper.getTenantId(), agent.getNodeId(), businessCallId, callId, customerCallId,
            agent.getAgentId(), agent.getExtension(), flowId);
    }

    @Override
    public CallControlResponse startConsultTransfer(String callId, String targetExtension, String phoneMode) {
        return startConsultTransfer(null, callId, targetExtension, phoneMode);
    }

    @Override
    public CallControlResponse startConsultTransfer(Long agentId, String callId, String targetExtension, String phoneMode) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
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
        String businessCallId = resolveAuthoritativeBusinessCallId(activeCall);
        String customerCallId = resolveCurrentCustomerLegId(endpoint, businessCallId, activeCall, agent);
        businessCallId = firstNotBlank(resolveBusinessCallIdByLegUuid(customerCallId), businessCallId, customerCallId);
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
        consultCall.setTenantId(TenantHelper.getTenantId());
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
            telephonyCommandGateway.setCallVariable(endpoint, customerCallId, "callnexus_satisfaction_skip", "true");
            prepareConsultBridge(endpoint, customerCallId, sourceAgentCallId);
            parkSourceAgentChannelIfExists(endpoint, consultCall);
            waitForConsultBridgeReleased();
            log.info("咨询转接原桥已拆分并驻留，businessCallId={}，counterpartyLegUuid={}，sourceAgentLegUuid={}",
                businessCallId, customerCallId, sourceAgentCallId);
            consultCall.setStatus(AgentConsultCallStatus.DIALING);
            saveConsultCall(agent, consultCall);
            telephonyCommandGateway.originateConsultation(endpoint, businessCallId, consultCallId, agent.getExtension(),
                targetExtension, customerCallId, sourceAgentCallId);
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
        cancelConsultTransfer(null, callId);
    }

    @Override
    public void cancelConsultTransfer(Long agentId, String callId) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
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
        completeConsultTransfer(null, callId);
    }

    @Override
    public void completeConsultTransfer(Long agentId, String callId) {
        CurrentAgentResponse agent = requireSignedInAgent(agentId);
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
        telephonyCommandGateway.bridgeCalls(endpoint, consultCall.getCustomerCallId(), consultCall.getTargetAgentCallId());
        waitForBridgeEstablished(endpoint, consultCall.getCustomerCallId(), consultCall.getTargetAgentCallId());
        markCompletedTransferSource(agent, activeCall, consultCall);
        prepareCompletedTransferBridge(endpoint, consultCall);
        hangupSourceAgentChannelIfExists(endpoint, consultCall);
        finishConsultTransfer(agent, activeCall, consultCall);
    }

    private void completeExternalSoftphoneConsultTransfer(CurrentAgentResponse agent, AgentActiveCall activeCall,
                                                          AgentConsultCall consultCall, EslEndpoint endpoint) {
        prepareExternalSoftphoneTargetForRebridge(endpoint, consultCall);
        telephonyCommandGateway.bridgeCalls(endpoint, consultCall.getCustomerCallId(), consultCall.getTargetAgentCallId());
        waitForBridgeEstablished(endpoint, consultCall.getCustomerCallId(), consultCall.getTargetAgentCallId());
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
        changeAgentStatus(agent, AgentPresenceStatus.AFTER_CALL);
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
        Set<String> sourceLegUuids = new LinkedHashSet<>();
        addCallId(sourceLegUuids, consultCall.getSourceAgentCallId());
        addCallId(sourceLegUuids, consultCall.getSourceAgentLegUuid());
        if (activeCall != null) {
            addCallId(sourceLegUuids, activeCall.getAgentChannelId());
        }
        markTransferredSource(agent, sourceLegUuids);
    }

    private void markBlindTransferSource(CurrentAgentResponse agent, AgentActiveCall activeCall) {
        if (agent == null || agent.getAgentId() == null) {
            return;
        }
        Set<String> sourceLegUuids = new LinkedHashSet<>();
        if (activeCall != null) {
            addCallId(sourceLegUuids, activeCall.getAgentChannelId());
        }
        markTransferredSource(agent, sourceLegUuids);
    }

    private void markTransferredSource(CurrentAgentResponse agent, Set<String> sourceLegUuids) {
        sourceLegUuids.forEach(uuid -> RedisUtils.setCacheObject(
                transferredSourceAgentKey(TenantHelper.getTenantId(), uuid, agent.getAgentId()),
            Boolean.TRUE,
            TRANSFERRED_SOURCE_TTL
        ));
        sourceLegUuids.forEach(uuid -> RedisUtils.setCacheObject(
                transferredSourceExtensionKey(TenantHelper.getTenantId(), uuid, agent.getExtension()),
            Boolean.TRUE,
            TRANSFERRED_SOURCE_TTL
        ));
        sourceLegUuids.forEach(uuid -> RedisUtils.setCacheObject(
            transferredSourceLegKey(uuid),
            Boolean.TRUE,
            TRANSFERRED_SOURCE_TTL
        ));
    }

    private String transferredSourceAgentKey(String tenantId, String sourceLegUuid, Long agentId) {
        return TRANSFERRED_SOURCE_AGENT_KEY_PREFIX + tenantId + ":" + sourceLegUuid + ":" + agentId;
    }

    private String transferredSourceExtensionKey(String tenantId, String sourceLegUuid, String extension) {
        return TRANSFERRED_SOURCE_EXTENSION_KEY_PREFIX + tenantId + ":" + sourceLegUuid + ":" + extension;
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
        return requireSignedInAgent(null);
    }

    private CurrentAgentResponse requireSignedInAgent(Long agentId) {
        CurrentAgentResponse agent = agentId == null
            ? agentSessionService.current()
            : explicitAgentSessionService.get(agentId);
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

    private void changeAgentStatus(CurrentAgentResponse agent, AgentPresenceStatus status) {
        if (agent == null || agent.getAgentId() == null) {
            throw new ServiceException("坐席信息不完整，无法更新状态");
        }
        explicitAgentSessionService.changeStatus(agent.getAgentId(), status);
    }

    private void restoreAgentStatusAfterOriginateFailure(CurrentAgentResponse agent) {
        try {
            explicitAgentSessionService.changeStatus(agent.getAgentId(), agent.getStatus());
        } catch (RuntimeException restoreException) {
            log.warn("外呼失败后恢复坐席状态失败，agentId={}，status={}，error={}",
                agent.getAgentId(), agent.getStatus(), restoreException.getMessage());
        }
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

    private String resolveAuthoritativeBusinessCallId(AgentActiveCall activeCall) {
        return firstNotBlank(resolveBusinessCallIdFromActiveCall(activeCall),
            activeCall == null ? null : activeCall.getBusinessCallId(),
            activeCall == null ? null : activeCall.getCallId());
    }

    private String resolveCurrentCustomerLegId(EslEndpoint endpoint, String businessCallId, AgentActiveCall activeCall,
                                               CurrentAgentResponse agent) {
        String cachedAgentChannelId = activeCall == null ? null : activeCall.getAgentChannelId();
        String legUuid = liveLegUuid(endpoint, activeLegByRole(businessCallId, "CUSTOMER"));
        if (legUuid != null) {
            if (legUuid.equals(cachedAgentChannelId)) {
                log.warn("活动通话缓存中的坐席腿与数据库客户腿冲突，按数据库客户腿处理，businessCallId={}，customerLegUuid={}，agentId={}，extension={}",
                    businessCallId, legUuid, agent == null ? null : agent.getAgentId(),
                    agent == null ? null : agent.getExtension());
            }
            return legUuid;
        }
        if (isInternalBusinessCall(businessCallId)) {
            legUuid = liveLegUuid(endpoint,
                activeInternalCounterpartyLeg(businessCallId, agent, cachedAgentChannelId));
            if (legUuid != null) {
                log.info("已从内部通话活动腿解析当前对端，businessCallId={}，counterpartyLegUuid={}，agentId={}，extension={}",
                    businessCallId, legUuid, agent == null ? null : agent.getAgentId(),
                    agent == null ? null : agent.getExtension());
                return legUuid;
            }
        }
        legUuid = candidateCallIds(activeCall, activeCall == null ? null : activeCall.getCallId()).stream()
            .filter(this::isUuid)
            .filter(uuid -> !uuid.equals(cachedAgentChannelId))
            .filter(uuid -> isCounterpartyLeg(uuid, agent))
            .filter(uuid -> telephonyCommandGateway.callExists(endpoint, uuid))
            .findFirst()
            .orElse(null);
        if (legUuid != null) {
            log.info("已从关联 UUID 解析当前通话对端腿，businessCallId={}，counterpartyLegUuid={}，counterpartyRole={}，activeCallId={}，agentId={}，extension={}",
                businessCallId, legUuid, legRoleByUuid(legUuid), activeCall == null ? null : activeCall.getCallId(),
                agent == null ? null : agent.getAgentId(), agent == null ? null : agent.getExtension());
            return legUuid;
        }
        String legacyCallId = liveOriginalCallId(endpoint, activeCall);
        if (legacyCallId == null || legRoleByUuid(legacyCallId) != null) {
            return null;
        }
        log.warn("当前通话缺少客户腿角色数据，兼容使用未落库角色的活动通道，businessCallId={}，customerLegUuid={}",
            businessCallId, legacyCallId);
        return legacyCallId;
    }

    private boolean isCounterpartyLeg(String legUuid, CurrentAgentResponse agent) {
        if (legUuid == null || legUuid.isBlank()) {
            return false;
        }
        CallLeg leg = callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .select(CallLeg::getLegRole, CallLeg::getAgentId, CallLeg::getAgentExtension, CallLeg::getEndpointExtension)
            .eq(CallLeg::getLegUuid, legUuid)
            .last("limit 1"));
        if (leg == null) {
            return false;
        }
        if ("CUSTOMER".equals(leg.getLegRole())) {
            return true;
        }
        if (agent == null) {
            return false;
        }
        if (leg.getAgentId() != null) {
            return !leg.getAgentId().equals(agent.getAgentId());
        }
        String endpointExtension = firstNotBlank(leg.getAgentExtension(), leg.getEndpointExtension());
        return endpointExtension != null && !endpointExtension.equals(agent.getExtension());
    }

    private String resolveBusinessCallIdByLegUuid(String legUuid) {
        if (legUuid == null || legUuid.isBlank()) {
            return null;
        }
        CallLeg leg = callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .select(CallLeg::getBusinessCallId)
            .eq(CallLeg::getLegUuid, legUuid)
            .last("limit 1"));
        return leg == null ? null : leg.getBusinessCallId();
    }

    private String resolveCurrentSourceAgentLegId(EslEndpoint endpoint, String businessCallId, CurrentAgentResponse agent,
                                                  AgentActiveCall activeCall, String customerCallId) {
        String legUuid = liveLegUuid(endpoint, activeAgentLeg(businessCallId, agent));
        if (legUuid != null && !legUuid.equals(customerCallId)) {
            return legUuid;
        }
        String cachedAgentChannelId = activeCall == null ? null : activeCall.getAgentChannelId();
        if (isUuid(cachedAgentChannelId)
            && !cachedAgentChannelId.equals(customerCallId)
            && telephonyCommandGateway.callExists(endpoint, cachedAgentChannelId)) {
            return cachedAgentChannelId;
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
            .in(CallLeg::getLegRole, List.of("AGENT", "CONSULT_AGENT", "PICKUP"))
            .last("order by create_time desc limit 1"));
    }

    private CallLeg activeInternalCounterpartyLeg(String businessCallId, CurrentAgentResponse agent,
                                                  String currentAgentLegUuid) {
        if (businessCallId == null || businessCallId.isBlank() || agent == null) {
            return null;
        }
        return callLegMapper.selectList(new LambdaQueryWrapper<CallLeg>()
                .eq(CallLeg::getBusinessCallId, businessCallId)
                .eq(CallLeg::getActive, true)
                .in(CallLeg::getLegRole, List.of("AGENT", "EXTENSION", "CONSULT_AGENT"))
                .orderByAsc(CallLeg::getCreateTime))
            .stream()
            .filter(leg -> currentAgentLegUuid == null || !currentAgentLegUuid.equals(leg.getLegUuid()))
            .filter(leg -> !isCurrentAgentLeg(leg, agent))
            .findFirst()
            .orElse(null);
    }

    private boolean isCurrentAgentLeg(CallLeg leg, CurrentAgentResponse agent) {
        if (leg == null || agent == null) {
            return false;
        }
        if (leg.getAgentId() != null && agent.getAgentId() != null) {
            return leg.getAgentId().equals(agent.getAgentId());
        }
        String legExtension = firstNotBlank(leg.getAgentExtension(), leg.getEndpointExtension());
        return legExtension != null && legExtension.equals(agent.getExtension());
    }

    private String liveLegUuid(EslEndpoint endpoint, CallLeg leg) {
        if (leg == null || leg.getLegUuid() == null || leg.getLegUuid().isBlank()) {
            return null;
        }
        return telephonyCommandGateway.callExists(endpoint, leg.getLegUuid()) ? leg.getLegUuid() : null;
    }

    private void validateConsultStartLegs(String businessCallId, String customerCallId, String sourceAgentCallId) {
        if (!isUuid(customerCallId) || !isUuid(sourceAgentCallId)) {
            log.warn("咨询转接三腿解析不完整，businessCallId={}，counterpartyLegUuid={}，sourceAgentLegUuid={}",
                businessCallId, customerCallId, sourceAgentCallId);
            throw new ServiceException("当前通话三腿信息不完整，无法发起咨询转接");
        }
        if (customerCallId.equals(sourceAgentCallId)) {
            throw new ServiceException("当前咨询转接客户腿和源坐席腿相同，拒绝发起咨询");
        }
        String customerRole = legRole(businessCallId, customerCallId);
        String sourceRole = legRole(businessCallId, sourceAgentCallId);
        if (customerRole != null && !isAllowedCounterpartyRole(businessCallId, customerRole)) {
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
        if (customerRole != null && !isAllowedCounterpartyRole(consultCall.getBusinessCallId(), customerRole)) {
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

    private String legRoleByUuid(String legUuid) {
        if (legUuid == null || legUuid.isBlank()) {
            return null;
        }
        CallLeg leg = callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .select(CallLeg::getLegRole)
            .eq(CallLeg::getLegUuid, legUuid)
            .last("limit 1"));
        return leg == null ? null : leg.getLegRole();
    }

    private boolean isAllowedCounterpartyRole(String businessCallId, String role) {
        if ("CUSTOMER".equals(role)) {
            return true;
        }
        return isInternalBusinessCall(businessCallId)
            && ("AGENT".equals(role) || "EXTENSION".equals(role) || "CONSULT_AGENT".equals(role));
    }

    private boolean isInternalBusinessCall(String businessCallId) {
        if (businessCallId == null || businessCallId.isBlank()) {
            return false;
        }
        CallSession session = callSessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .select(CallSession::getDirection)
            .eq(CallSession::getBusinessCallId, businessCallId)
            .last("limit 1"));
        return session != null && "INTERNAL".equalsIgnoreCase(session.getDirection());
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
        return ACTIVE_CALL_KEY_PREFIX + TenantHelper.getTenantId() + ":" + agentId;
    }

    private String activeCallKey(String tenantId, Long agentId) {
        return ACTIVE_CALL_KEY_PREFIX + tenantId + ":" + agentId;
    }

    private String consultCallKey(Long agentId) {
        return CONSULT_CALL_KEY_PREFIX + TenantHelper.getTenantId() + ":" + agentId;
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
        if (consultCall.getSourceAgentCallId() != null && telephonyCommandGateway.callExists(endpoint, consultCall.getSourceAgentCallId())
            && telephonyCommandGateway.callExists(endpoint, consultCall.getCustomerCallId())) {
            telephonyCommandGateway.bridgeCalls(endpoint, consultCall.getSourceAgentCallId(), consultCall.getCustomerCallId());
            waitForBridgeEstablished(endpoint, consultCall.getSourceAgentCallId(), consultCall.getCustomerCallId());
        }
    }

    private void waitForBridgeEstablished(EslEndpoint endpoint, String leftCallId, String rightCallId) {
        long deadline = System.nanoTime() + Duration.ofMillis(CONSULT_BRIDGE_CONFIRM_TIMEOUT_MILLIS).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (telephonyCommandGateway.callsAreBridged(endpoint, leftCallId, rightCallId)) {
                    log.info("咨询转接媒体桥已确认，leftCallId={}，rightCallId={}", leftCallId, rightCallId);
                    return;
                }
            } catch (RuntimeException exception) {
                log.debug("查询咨询转接媒体桥状态失败，稍后重试，leftCallId={}，rightCallId={}，error={}",
                    leftCallId, rightCallId, exception.getMessage());
            }
            try {
                Thread.sleep(CONSULT_BRIDGE_CONFIRM_INTERVAL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("等待咨询转接媒体桥确认超时，继续尝试恢复媒体，leftCallId={}，rightCallId={}，timeoutMs={}",
            leftCallId, rightCallId, CONSULT_BRIDGE_CONFIRM_TIMEOUT_MILLIS);
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

    private void recoverMediaIfPossible(EslEndpoint endpoint, String callId) {
        try {
            telephonyCommandGateway.recoverMedia(endpoint, callId);
        } catch (RuntimeException exception) {
            log.warn("恢复桥接媒体失败，继续执行后续转接流程，callId={}，error={}", callId, exception.getMessage());
        }
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
        String businessCallId = resolveAuthoritativeBusinessCallId(activeCall);
        String customerLegUuid = resolveCurrentCustomerLegId(endpoint, businessCallId, activeCall, agent);
        if (customerLegUuid == null || customerLegUuid.isBlank() || !telephonyCommandGateway.callExists(endpoint, customerLegUuid)) {
            throw new ServiceException("当前客户通话腿不存在，无法执行坐席控制动作");
        }
        String customerRole = legRole(businessCallId, customerLegUuid);
        if (customerRole != null && !isAllowedCounterpartyRole(businessCallId, customerRole)) {
            throw new ServiceException("当前业务通话的客户腿识别异常，已拒绝执行坐席控制动作");
        }
        String agentLegUuid = resolveCurrentSourceAgentLegId(endpoint, businessCallId, agent, activeCall, customerLegUuid);
        if (agentLegUuid == null || agentLegUuid.isBlank() || !telephonyCommandGateway.callExists(endpoint, agentLegUuid)) {
            throw new ServiceException("当前坐席通话腿不存在，无法执行坐席控制动作");
        }
        String agentRole = legRole(businessCallId, agentLegUuid);
        boolean resolvedFromActiveAgentChannel = agentLegUuid.equals(activeCall.getAgentChannelId());
        if ("CUSTOMER".equals(agentRole) && !resolvedFromActiveAgentChannel) {
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
            .filter(uuid -> !uuid.equals(customerCallId))
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

    private void notifyConsultSourceTransferCompleted(CurrentAgentResponse sourceAgent, AgentActiveCall sourceCall, AgentConsultCall consultCall) {
        if (sourceAgent == null || sourceAgent.getUserId() == null || sourceCall == null) {
            return;
        }
        Set<String> finishedCallIds = new LinkedHashSet<>();
        addCallId(finishedCallIds, consultCall == null ? null : consultCall.getSourceAgentCallId());
        addCallId(finishedCallIds, consultCall == null ? null : consultCall.getSourceAgentLegUuid());
        addCallId(finishedCallIds, sourceCall.getAgentChannelId());
        if (finishedCallIds.isEmpty()) {
            addCallId(finishedCallIds, sourceCall.getCallId());
        }
        finishedCallIds.forEach(callId -> publishConsultSourceTransferCompleted(sourceAgent, sourceCall, callId));
    }

    private void publishConsultSourceTransferCompleted(CurrentAgentResponse sourceAgent, AgentActiveCall sourceCall, String callId) {
        CallRealtimeMessage message = new CallRealtimeMessage();
        message.setType("CALL_HANGUP_COMPLETE");
        message.setCallId(callId);
        message.setBusinessCallId(firstNotBlank(sourceCall.getBusinessCallId(), sourceCall.getCallId(), callId));
        message.setLegUuid(callId);
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
        message.setCallId(targetCall.getCallId());
        message.setBusinessCallId(targetCall.getBusinessCallId());
        message.setLegUuid(targetCall.getAgentChannelId());
        message.setCallerNumber(sourceCall.getDestination());
        message.setCalledNumber(target.getExtension());
        message.setAgentExtension(target.getExtension());
        message.setOccurredAt(LocalDateTime.now());
        publishRealtimeMessage(target.getUserId(), JsonUtils.toJsonString(message));
        log.info("已通知转接目标坐席接管通话，targetAgentId={}，targetExtension={}，businessCallId={}，frontendCallId={}，targetAgentLegUuid={}，sourceCallId={}",
            target.getAgentId(), target.getExtension(), targetCall.getBusinessCallId(), targetCall.getCallId(),
            targetCall.getAgentChannelId(), sourceCall.getCallId());
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
            TenantHelper.getTenantId(),
            "AGENT_ORIGINATE",
            agent.getNodeId(),
            agent.getSipDomain(),
            null,
            agent.getAgentId(),
            agent.getUserId(),
            context == null ? null : context.skillGroupId(),
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
        if (result.external() && context != null && context.allowedOutboundPolicyCodes() != null) {
            String policyCode = result.outboundRoute() == null ? null : result.outboundRoute().getPolicyCode();
            if (policyCode == null || !context.allowedOutboundPolicyCodes().contains(policyCode)) {
                throw new ServiceException("开放应用未获授权使用本次外呼线路策略");
            }
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
            return new CallOriginateContext(businessCallId, null, null, null, null, null, null);
        }
        return new CallOriginateContext(
            businessCallId,
            context.customerId(),
            context.outboundTaskId(),
            context.outboundMemberId(),
            context.callerNumberId(),
            context.skillGroupId(),
            context.allowedOutboundPolicyCodes()
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
