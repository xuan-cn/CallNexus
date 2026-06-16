package org.dromara.agent.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.service.AgentAvailabilityQueryService;
import org.dromara.agent.service.model.AgentAvailability;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentAvailabilityQueryServiceImpl implements AgentAvailabilityQueryService {

    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";

    private final AgentMapper agentMapper;

    @Override
    public AgentAvailability get(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            return null;
        }
        AgentPresence presence = RedisUtils.getCacheObject(
            PRESENCE_KEY_PREFIX + TenantHelper.getTenantId() + ":" + agentId);
        return new AgentAvailability(agent.getId(), agent.getUserId(), agent.getAgentName(),
            Boolean.TRUE.equals(agent.getEnabled()),
            presence != null && presence.getStatus() == AgentPresenceStatus.IDLE);
    }
}

