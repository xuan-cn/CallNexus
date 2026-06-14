package org.dromara.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.service.AgentRealtimeQueryService;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.sip.domain.response.SipAccountRealtimeResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentRealtimeQueryServiceImpl implements AgentRealtimeQueryService {
    private final SipAccountQueryService sipAccountQueryService;
    private final AgentExtensionMapper extensionMapper;
    private final AgentMapper agentMapper;

    @Override
    public AgentRealtimeTargetResponse findByNodeAndExtension(Long nodeId, String extension) {
        if (nodeId == null || extension == null || extension.isBlank()) return null;
        return TenantHelper.ignore(() -> findIgnoringTenant(nodeId, extension));
    }

    private AgentRealtimeTargetResponse findIgnoringTenant(Long nodeId, String extension) {
        SipAccountRealtimeResponse sipAccount = sipAccountQueryService.findEnabledByNodeAndExtension(nodeId, extension);
        if (sipAccount == null) return null;
        AgentExtension binding = extensionMapper.selectOne(new LambdaQueryWrapper<AgentExtension>()
            .eq(AgentExtension::getSipAccountId, sipAccount.getSipAccountId()));
        if (binding == null) return null;
        Agent agent = agentMapper.selectById(binding.getAgentId());
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) return null;
        AgentRealtimeTargetResponse response = new AgentRealtimeTargetResponse();
        response.setTenantId(agent.getTenantId());
        response.setAgentId(agent.getId());
        response.setUserId(agent.getUserId());
        response.setNodeId(sipAccount.getNodeId());
        response.setExtension(sipAccount.getExtension());
        response.setSipDomain(sipAccount.getDomain());
        return response;
    }
}
