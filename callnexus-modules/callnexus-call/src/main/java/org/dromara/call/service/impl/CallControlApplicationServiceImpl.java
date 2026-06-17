package org.dromara.call.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationCommand;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationResult;
import org.dromara.resource.outboundauth.service.OutboundAuthorizationService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CallControlApplicationServiceImpl implements CallControlApplicationService {
    private static final String ACTIVE_CALL_KEY_PREFIX = "callnexus:agent:active-call:";
    private static final Duration ACTIVE_CALL_TTL = Duration.ofHours(4);

    private final CurrentAgentSessionService agentSessionService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway telephonyCommandGateway;
    private final OutboundAuthorizationService outboundAuthorizationService;

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
                throw new ServiceException("当前坐席已存在通话，请先挂断");
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
        String key = activeCallKey(agent.getAgentId());
        AgentActiveCall activeCall = RedisUtils.getCacheObject(key);
        if (activeCall == null || !activeCall.getCallId().equals(callId)) {
            throw new ServiceException("当前通话不存在或已结束");
        }
        telephonyCommandGateway.hangup(endpoint(agent.getNodeId()), callId);
        RedisUtils.deleteObject(key);
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

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }

    private String activeCallKey(Long agentId) {
        return ACTIVE_CALL_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agentId;
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
