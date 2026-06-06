package org.dromara.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.request.*;
import org.dromara.agent.domain.response.AgentResponse;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.service.AgentApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentApplicationServiceImpl implements AgentApplicationService {
    private final AgentMapper agentMapper;
    private final AgentExtensionMapper extensionMapper;
    private final SipAccountQueryService sipAccountQueryService;

    @Override
    public TableDataInfo<AgentResponse> page(AgentPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<Agent>()
            .like(query.getAgentCode() != null && !query.getAgentCode().isBlank(), Agent::getAgentCode, query.getAgentCode())
            .like(query.getAgentName() != null && !query.getAgentName().isBlank(), Agent::getAgentName, query.getAgentName())
            .eq(query.getEnabled() != null, Agent::getEnabled, query.getEnabled())
            .orderByAsc(Agent::getAgentCode);
        Page<Agent> page = agentMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public AgentResponse get(Long id) {
        Agent agent = agentMapper.selectById(id);
        if (agent == null) throw new ServiceException("AGENT_NOT_FOUND");
        return toResponse(agent);
    }

    @Override
    public Long create(CreateAgentRequest request) {
        ensureAgentCodeUnique(request.getAgentCode(), null);
        ensureUserUnique(request.getUserId(), null);
        Agent agent = new Agent();
        agent.setAgentCode(request.getAgentCode());
        agent.setAgentName(request.getAgentName());
        agent.setUserId(request.getUserId());
        agent.setEnabled(true);
        agentMapper.insert(agent);
        return agent.getId();
    }

    @Override
    public void update(Long id, UpdateAgentRequest request) {
        ensureAgentCodeUnique(request.getAgentCode(), id);
        ensureUserUnique(request.getUserId(), id);
        Agent agent = agentMapper.selectById(id);
        if (agent == null) throw new ServiceException("AGENT_NOT_FOUND");
        agent.setAgentCode(request.getAgentCode());
        agent.setAgentName(request.getAgentName());
        agent.setUserId(request.getUserId());
        agent.setEnabled(request.getEnabled());
        agent.setVersion(request.getVersion());
        if (agentMapper.updateById(agent) != 1) throw new ServiceException("AGENT_UPDATE_CONFLICT");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        extensionMapper.delete(new LambdaQueryWrapper<AgentExtension>().eq(AgentExtension::getAgentId, id));
        if (agentMapper.deleteById(id) != 1) throw new ServiceException("AGENT_NOT_FOUND");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindExtension(Long agentId, BindAgentExtensionRequest request) {
        if (agentMapper.selectById(agentId) == null) throw new ServiceException("AGENT_NOT_FOUND");
        if (!sipAccountQueryService.existsEnabled(request.getSipAccountId())) throw new ServiceException("SIP_ACCOUNT_NOT_FOUND_OR_DISABLED");
        boolean occupied = extensionMapper.exists(new LambdaQueryWrapper<AgentExtension>()
            .eq(AgentExtension::getSipAccountId, request.getSipAccountId())
            .ne(AgentExtension::getAgentId, agentId));
        if (occupied) throw new ServiceException("SIP_ACCOUNT_ALREADY_BOUND");
        extensionMapper.delete(new LambdaQueryWrapper<AgentExtension>().eq(AgentExtension::getAgentId, agentId));
        AgentExtension binding = new AgentExtension();
        binding.setAgentId(agentId);
        binding.setSipAccountId(request.getSipAccountId());
        extensionMapper.insert(binding);
    }

    @Override
    public void unbindExtension(Long agentId) {
        extensionMapper.delete(new LambdaQueryWrapper<AgentExtension>().eq(AgentExtension::getAgentId, agentId));
    }

    private void ensureAgentCodeUnique(String agentCode, Long excludedId) {
        boolean exists = agentMapper.exists(new LambdaQueryWrapper<Agent>()
            .eq(Agent::getAgentCode, agentCode)
            .ne(excludedId != null, Agent::getId, excludedId));
        if (exists) throw new ServiceException("AGENT_CODE_ALREADY_EXISTS");
    }

    private void ensureUserUnique(Long userId, Long excludedId) {
        if (userId == null) return;
        boolean exists = agentMapper.exists(new LambdaQueryWrapper<Agent>()
            .eq(Agent::getUserId, userId)
            .ne(excludedId != null, Agent::getId, excludedId));
        if (exists) throw new ServiceException("AGENT_USER_ALREADY_BOUND");
    }

    private AgentResponse toResponse(Agent agent) {
        AgentResponse response = new AgentResponse();
        response.setId(agent.getId());
        response.setAgentCode(agent.getAgentCode());
        response.setAgentName(agent.getAgentName());
        response.setUserId(agent.getUserId());
        response.setEnabled(agent.getEnabled());
        response.setVersion(agent.getVersion());
        response.setCreateTime(agent.getCreateTime());
        return response;
    }
}
