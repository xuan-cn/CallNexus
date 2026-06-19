package org.dromara.call.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.agent.domain.AgentConsultCall;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.AgentRealtimeQueryService;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.response.CallRealtimeMessage;
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
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CallControlApplicationServiceImpl implements CallControlApplicationService {
    private static final String ACTIVE_CALL_KEY_PREFIX = "callnexus:agent:active-call:";
    private static final String CONSULT_CALL_KEY_PREFIX = "callnexus:agent:consult-call:";
    private static final Duration ACTIVE_CALL_TTL = Duration.ofHours(4);

    private final CurrentAgentSessionService agentSessionService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway telephonyCommandGateway;
    private final OutboundAuthorizationService outboundAuthorizationService;
    private final AgentRealtimeQueryService agentRealtimeQueryService;

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

        String callId = context != null && context.businessCallId() != null && !context.businessCallId().isBlank()
            ? context.businessCallId() : UUID.randomUUID().toString();
        OutboundAuthorizationResult authorization = authorizeOutbound(agent, destination, context);
        OutboundRoute outboundRoute = toOutboundRoute(authorization);
        String authorizedDestination = authorization.normalizedCallee();
        telephonyCommandGateway.originate(endpoint(agent.getNodeId()), callId, agent.getExtension(), authorizedDestination, outboundRoute,
            context == null ? CallOriginateContext.empty() : context);

        AgentActiveCall activeCall = new AgentActiveCall();
        activeCall.setCallId(callId);
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
    public void blindTransfer(String callId, String targetExtension) {
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        telephonyCommandGateway.blindTransfer(endpoint(agent.getNodeId()), callId, targetExtension);
        notifyTransferredTargetAgent(agent, activeCall, targetExtension);
        RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
        RedisUtils.deleteObject(consultCallKey(agent.getAgentId()));
        agentSessionService.changeStatus(AgentPresenceStatus.AFTER_CALL);
    }

    @Override
    public CallControlResponse startConsultTransfer(String callId, String targetExtension) {
        CurrentAgentResponse agent = requireSignedInAgent();
        requireActiveCall(agent, callId);
        AgentConsultCall existing = RedisUtils.getCacheObject(consultCallKey(agent.getAgentId()));
        if (existing != null) {
            if (telephonyCommandGateway.callExists(endpoint(agent.getNodeId()), existing.getConsultCallId())) {
                throw new ServiceException("当前坐席已有咨询通话，请先完成或取消咨询转接");
            }
            RedisUtils.deleteObject(consultCallKey(agent.getAgentId()));
        }

        telephonyCommandGateway.hold(endpoint(agent.getNodeId()), callId);

        AgentConsultCall consultCall = new AgentConsultCall();
        consultCall.setOriginalCallId(callId);
        consultCall.setAgentId(agent.getAgentId());
        consultCall.setAgentExtension(agent.getExtension());
        consultCall.setTargetExtension(targetExtension);
        consultCall.setStartedAt(LocalDateTime.now());
        RedisUtils.setCacheObject(consultCallKey(agent.getAgentId()), consultCall, ACTIVE_CALL_TTL);

        CallControlResponse response = new CallControlResponse();
        response.setCallId(callId);
        response.setAgentExtension(agent.getExtension());
        response.setDestination(targetExtension);
        response.setExternal(false);
        response.setStatus("CUSTOMER_HELD");
        return response;
    }

    @Override
    public void cancelConsultTransfer(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        requireActiveCall(agent, callId);
        AgentConsultCall consultCall = requireConsultCall(agent, callId);
        hangupConsultCallIfExists(agent, consultCall);
        telephonyCommandGateway.unhold(endpoint(agent.getNodeId()), callId);
        RedisUtils.deleteObject(consultCallKey(agent.getAgentId()));
    }

    @Override
    public void completeConsultTransfer(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        AgentActiveCall activeCall = requireActiveCall(agent, callId);
        AgentConsultCall consultCall = requireConsultCall(agent, callId);
        hangupConsultCallIfExists(agent, consultCall);
        telephonyCommandGateway.blindTransfer(endpoint(agent.getNodeId()), callId, consultCall.getTargetExtension());
        notifyTransferredTargetAgent(agent, activeCall, consultCall.getTargetExtension());
        RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
        RedisUtils.deleteObject(consultCallKey(agent.getAgentId()));
        agentSessionService.changeStatus(AgentPresenceStatus.AFTER_CALL);
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
        if (activeCall == null || !activeCall.getCallId().equals(callId)) {
            throw new ServiceException("当前通话不存在或已结束");
        }
        if (!telephonyCommandGateway.callExists(endpoint(agent.getNodeId()), callId)) {
            RedisUtils.deleteObject(activeCallKey(agent.getAgentId()));
            throw new ServiceException("当前通话已在 FreeSWITCH 侧结束");
        }
        return activeCall;
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

    private AgentConsultCall requireConsultCall(CurrentAgentResponse agent, String callId) {
        AgentConsultCall consultCall = RedisUtils.getCacheObject(consultCallKey(agent.getAgentId()));
        if (consultCall == null || !callId.equals(consultCall.getOriginalCallId())) {
            throw new ServiceException("当前没有进行中的咨询转接");
        }
        return consultCall;
    }

    private void hangupConsultCallIfExists(CurrentAgentResponse agent, AgentConsultCall consultCall) {
        if (consultCall.getConsultCallId() == null || consultCall.getConsultCallId().isBlank()) {
            return;
        }
        if (telephonyCommandGateway.callExists(endpoint(agent.getNodeId()), consultCall.getConsultCallId())) {
            telephonyCommandGateway.hangup(endpoint(agent.getNodeId()), consultCall.getConsultCallId());
        }
    }

    private void notifyTransferredTargetAgent(CurrentAgentResponse sourceAgent, AgentActiveCall sourceCall, String targetExtension) {
        AgentRealtimeTargetResponse target = agentRealtimeQueryService.findByNodeAndExtension(sourceAgent.getNodeId(), targetExtension);
        if (target == null || target.getUserId() == null) {
            return;
        }
        AgentActiveCall targetCall = new AgentActiveCall();
        targetCall.setCallId(sourceCall.getCallId());
        targetCall.setAgentId(target.getAgentId());
        targetCall.setAgentExtension(target.getExtension());
        targetCall.setDestination(sourceCall.getDestination());
        targetCall.setExternal(sourceCall.getExternal());
        targetCall.setGatewayCode(sourceCall.getGatewayCode());
        targetCall.setCallerIdNumber(sourceCall.getCallerIdNumber());
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
        response.setAgentExtension(call.getAgentExtension());
        response.setDestination(call.getDestination());
        response.setExternal(call.getExternal());
        response.setGatewayCode(call.getGatewayCode());
        response.setCallerIdNumber(call.getCallerIdNumber());
        response.setStatus("DIALING");
        return response;
    }
}
