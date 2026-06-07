package org.dromara.call.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.call.domain.ActiveCall;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
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

    @Override
    public CallControlResponse originate(String destination) {
        CurrentAgentResponse agent = requireSignedInAgent();
        String key = activeCallKey(agent.getAgentId());
        ActiveCall existingCall = RedisUtils.getCacheObject(key);
        if (existingCall != null) {
            if (telephonyCommandGateway.callExists(endpoint(agent.getNodeId()), existingCall.getCallId())) {
                throw new ServiceException("AGENT_ALREADY_HAS_ACTIVE_CALL");
            }
            RedisUtils.deleteObject(key);
        }

        String callId = UUID.randomUUID().toString();
        telephonyCommandGateway.originate(endpoint(agent.getNodeId()), callId, agent.getExtension(), destination);

        ActiveCall activeCall = new ActiveCall();
        activeCall.setCallId(callId);
        activeCall.setAgentId(agent.getAgentId());
        activeCall.setAgentExtension(agent.getExtension());
        activeCall.setDestination(destination);
        RedisUtils.setCacheObject(key, activeCall, ACTIVE_CALL_TTL);
        agentSessionService.changeStatus(AgentPresenceStatus.BUSY);
        return toResponse(activeCall);
    }

    @Override
    public void hangup(String callId) {
        CurrentAgentResponse agent = requireSignedInAgent();
        String key = activeCallKey(agent.getAgentId());
        ActiveCall activeCall = RedisUtils.getCacheObject(key);
        if (activeCall == null || !activeCall.getCallId().equals(callId)) {
            throw new ServiceException("ACTIVE_CALL_NOT_FOUND");
        }
        telephonyCommandGateway.hangup(endpoint(agent.getNodeId()), callId);
        RedisUtils.deleteObject(key);
        agentSessionService.changeStatus(AgentPresenceStatus.AFTER_CALL);
    }

    private CurrentAgentResponse requireSignedInAgent() {
        CurrentAgentResponse agent = agentSessionService.current();
        if (!agent.isConfigured()) {
            throw new ServiceException("CURRENT_USER_NOT_BOUND_TO_AGENT");
        }
        if (agent.getStatus() == AgentPresenceStatus.OFFLINE) {
            throw new ServiceException("AGENT_NOT_SIGNED_IN");
        }
        if (agent.getNodeId() == null || agent.getExtension() == null || agent.getExtension().isBlank()) {
            throw new ServiceException("AGENT_SIP_ACCOUNT_NOT_BOUND_OR_DISABLED");
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

    private CallControlResponse toResponse(ActiveCall call) {
        CallControlResponse response = new CallControlResponse();
        response.setCallId(call.getCallId());
        response.setAgentExtension(call.getAgentExtension());
        response.setDestination(call.getDestination());
        response.setStatus("DIALING");
        return response;
    }
}
