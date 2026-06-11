package org.dromara.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.domain.response.SipRegistrationConfigResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CurrentAgentSessionServiceImpl implements CurrentAgentSessionService {
    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";
    private static final String ACTIVE_CALL_KEY_PREFIX = "callnexus:agent:active-call:";
    private static final Duration PRESENCE_TTL = Duration.ofHours(12);

    private final AgentMapper agentMapper;
    private final AgentExtensionMapper extensionMapper;
    private final SipAccountQueryService sipAccountQueryService;

    @Override
    public CurrentAgentResponse current() {
        Agent agent = findCurrentAgent();
        if (agent == null) {
            CurrentAgentResponse response = new CurrentAgentResponse();
            response.setConfigured(false);
            response.setStatus(AgentPresenceStatus.OFFLINE);
            return response;
        }
        return buildResponse(agent, getPresence(agent.getId()));
    }

    @Override
    public CurrentAgentResponse signIn() {
        Agent agent = requireCurrentAgent();
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new ServiceException("AGENT_DISABLED");
        }
        requireEnabledSipAccount(agent.getId());
        LocalDateTime now = LocalDateTime.now();
        AgentPresence presence = new AgentPresence();
        presence.setAgentId(agent.getId());
        presence.setStatus(AgentPresenceStatus.IDLE);
        presence.setSignedInAt(now);
        presence.setUpdatedAt(now);
        savePresence(agent.getId(), presence);
        return buildResponse(agent, presence);
    }

    @Override
    public CurrentAgentResponse changeStatus(AgentPresenceStatus status) {
        if (status == AgentPresenceStatus.OFFLINE) {
            throw new ServiceException("AGENT_STATUS_OFFLINE_REQUIRES_SIGN_OUT");
        }
        Agent agent = requireCurrentAgent();
        AgentPresence presence = getPresence(agent.getId());
        if (presence == null) {
            throw new ServiceException("AGENT_NOT_SIGNED_IN");
        }
        presence.setStatus(status);
        presence.setUpdatedAt(LocalDateTime.now());
        savePresence(agent.getId(), presence);
        return buildResponse(agent, presence);
    }

    @Override
    public void signOut() {
        Agent agent = requireCurrentAgent();
        RedisUtils.deleteObject(presenceKey(agent.getId()));
    }

    private Agent findCurrentAgent() {
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
            .eq(Agent::getUserId, LoginHelper.getUserId()));
    }

    private Agent requireCurrentAgent() {
        Agent agent = findCurrentAgent();
        if (agent == null) {
            throw new ServiceException("CURRENT_USER_NOT_BOUND_TO_AGENT");
        }
        return agent;
    }

    private void requireEnabledSipAccount(Long agentId) {
        AgentExtension binding = findExtension(agentId);
        if (binding == null || !sipAccountQueryService.existsEnabled(binding.getSipAccountId())) {
            throw new ServiceException("AGENT_SIP_ACCOUNT_NOT_BOUND_OR_DISABLED");
        }
        sipAccountQueryService.getRegistrationConfig(binding.getSipAccountId());
    }

    private AgentExtension findExtension(Long agentId) {
        return extensionMapper.selectOne(new LambdaQueryWrapper<AgentExtension>()
            .eq(AgentExtension::getAgentId, agentId));
    }

    private AgentPresence getPresence(Long agentId) {
        return RedisUtils.getCacheObject(presenceKey(agentId));
    }

    private void savePresence(Long agentId, AgentPresence presence) {
        RedisUtils.setCacheObject(presenceKey(agentId), presence, PRESENCE_TTL);
    }

    private String presenceKey(Long agentId) {
        return PRESENCE_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agentId;
    }

    private CurrentAgentResponse buildResponse(Agent agent, AgentPresence presence) {
        CurrentAgentResponse response = new CurrentAgentResponse();
        response.setConfigured(true);
        response.setAgentId(agent.getId());
        response.setAgentCode(agent.getAgentCode());
        response.setAgentName(agent.getAgentName());
        response.setUserId(agent.getUserId());
        AgentExtension binding = findExtension(agent.getId());
        if (binding != null) {
            response.setSipAccountId(binding.getSipAccountId());
            SipAccountResponse sipAccount = sipAccountQueryService.get(binding.getSipAccountId());
            if (sipAccount != null) {
                response.setExtension(sipAccount.getExtension());
                response.setSipDisplayName(sipAccount.getDisplayName());
            }
            if (sipAccount != null && sipAccount.getNodeId() != null) {
                SipRegistrationConfigResponse registrationConfig = sipAccountQueryService.getRegistrationConfig(binding.getSipAccountId());
                response.setNodeId(registrationConfig.getNodeId());
                response.setSipDomain(registrationConfig.getSipDomain());
                response.setWssUrl(registrationConfig.getWssUrl());
            }
        }
        response.setStatus(presence == null ? AgentPresenceStatus.OFFLINE : presence.getStatus());
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(agent.getId()));
        if (activeCall != null) {
            response.setActiveCallId(activeCall.getCallId());
            response.setActiveCallNumber(activeCall.getDestination());
        }
        if (presence != null) {
            response.setSignedInAt(presence.getSignedInAt());
            response.setUpdatedAt(presence.getUpdatedAt());
        }
        return response;
    }

    private String activeCallKey(Long agentId) {
        return ACTIVE_CALL_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agentId;
    }
}
