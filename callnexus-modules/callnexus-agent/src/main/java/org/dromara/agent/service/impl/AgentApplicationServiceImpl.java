package org.dromara.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.CallQueue;
import org.dromara.agent.domain.SkillGroupMember;
import org.dromara.agent.domain.request.*;
import org.dromara.agent.domain.response.AgentResponse;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.mapper.CallQueueMapper;
import org.dromara.agent.mapper.SkillGroupMemberMapper;
import org.dromara.agent.service.AgentApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentApplicationServiceImpl implements AgentApplicationService {
    private final AgentMapper agentMapper;
    private final AgentExtensionMapper extensionMapper;
    private final SkillGroupMemberMapper skillGroupMemberMapper;
    private final CallQueueMapper callQueueMapper;
    private final SipAccountQueryService sipAccountQueryService;

    @Override
    public TableDataInfo<AgentResponse> page(AgentPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<Agent>()
            .like(query.getAgentCode() != null && !query.getAgentCode().isBlank(), Agent::getAgentCode, query.getAgentCode())
            .like(query.getAgentName() != null && !query.getAgentName().isBlank(), Agent::getAgentName, query.getAgentName())
            .eq(query.getEnabled() != null, Agent::getEnabled, query.getEnabled())
            .orderByAsc(Agent::getAgentCode);
        Page<Agent> page = agentMapper.selectPage(pageQuery.build(), wrapper);
        Map<Long, Long> extensionBindings = findExtensionBindings(page.getRecords().stream().map(Agent::getId).toList());
        return new TableDataInfo<>(page.getRecords().stream().map(agent -> toResponse(agent, extensionBindings.get(agent.getId()))).toList(), page.getTotal());
    }

    @Override
    public AgentResponse get(Long id) {
        Agent agent = agentMapper.selectById(id);
        if (agent == null) throw new ServiceException("坐席不存在");
        AgentExtension binding = extensionMapper.selectOne(new LambdaQueryWrapper<AgentExtension>().eq(AgentExtension::getAgentId, id));
        return toResponse(agent, binding == null ? null : binding.getSipAccountId());
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
        if (agent == null) throw new ServiceException("坐席不存在");
        agent.setAgentCode(request.getAgentCode());
        agent.setAgentName(request.getAgentName());
        agent.setUserId(request.getUserId());
        agent.setEnabled(request.getEnabled());
        agent.setVersion(request.getVersion());
        if (agentMapper.updateById(agent) != 1) throw new ServiceException("坐席信息已被其他用户修改，请刷新后重试");
        markQueuesNotSynced(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (skillGroupMemberMapper.exists(new LambdaQueryWrapper<SkillGroupMember>().eq(SkillGroupMember::getAgentId, id))) {
            throw new ServiceException("坐席已加入技能组，无法删除");
        }
        extensionMapper.delete(new LambdaQueryWrapper<AgentExtension>().eq(AgentExtension::getAgentId, id));
        if (agentMapper.deleteById(id) != 1) throw new ServiceException("坐席不存在");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindExtension(Long agentId, BindAgentExtensionRequest request) {
        if (agentMapper.selectById(agentId) == null) throw new ServiceException("坐席不存在");
        if (!sipAccountQueryService.existsEnabled(request.getSipAccountId())) throw new ServiceException("SIP 分机不存在或已停用");
        boolean occupied = extensionMapper.exists(new LambdaQueryWrapper<AgentExtension>()
            .eq(AgentExtension::getSipAccountId, request.getSipAccountId())
            .ne(AgentExtension::getAgentId, agentId));
        if (occupied) throw new ServiceException("该 SIP 分机已被其他坐席绑定");
        extensionMapper.delete(new LambdaQueryWrapper<AgentExtension>().eq(AgentExtension::getAgentId, agentId));
        AgentExtension binding = new AgentExtension();
        binding.setAgentId(agentId);
        binding.setSipAccountId(request.getSipAccountId());
        extensionMapper.insert(binding);
        markQueuesNotSynced(agentId);
    }

    @Override
    public void unbindExtension(Long agentId) {
        extensionMapper.delete(new LambdaQueryWrapper<AgentExtension>().eq(AgentExtension::getAgentId, agentId));
        markQueuesNotSynced(agentId);
    }

    private void ensureAgentCodeUnique(String agentCode, Long excludedId) {
        boolean exists = agentMapper.exists(new LambdaQueryWrapper<Agent>()
            .eq(Agent::getAgentCode, agentCode)
            .ne(excludedId != null, Agent::getId, excludedId));
        if (exists) throw new ServiceException("坐席工号已存在");
    }

    private void ensureUserUnique(Long userId, Long excludedId) {
        if (userId == null) return;
        boolean exists = agentMapper.exists(new LambdaQueryWrapper<Agent>()
            .eq(Agent::getUserId, userId)
            .ne(excludedId != null, Agent::getId, excludedId));
        if (exists) throw new ServiceException("该用户已绑定其他坐席");
    }

    private Map<Long, Long> findExtensionBindings(List<Long> agentIds) {
        if (agentIds.isEmpty()) return Collections.emptyMap();
        return extensionMapper.selectList(new LambdaQueryWrapper<AgentExtension>().in(AgentExtension::getAgentId, agentIds)).stream()
            .collect(Collectors.toMap(AgentExtension::getAgentId, AgentExtension::getSipAccountId));
    }

    private AgentResponse toResponse(Agent agent, Long sipAccountId) {
        AgentResponse response = new AgentResponse();
        response.setId(agent.getId());
        response.setAgentCode(agent.getAgentCode());
        response.setAgentName(agent.getAgentName());
        response.setUserId(agent.getUserId());
        response.setSipAccountId(sipAccountId);
        response.setEnabled(agent.getEnabled());
        response.setVersion(agent.getVersion());
        response.setCreateTime(agent.getCreateTime());
        return response;
    }

    private void markQueuesNotSynced(Long agentId) {
        List<Long> skillGroupIds = skillGroupMemberMapper.selectList(new LambdaQueryWrapper<SkillGroupMember>()
                .eq(SkillGroupMember::getAgentId, agentId))
            .stream().map(SkillGroupMember::getSkillGroupId).distinct().toList();
        if (skillGroupIds.isEmpty()) return;
        for (CallQueue queue : callQueueMapper.selectList(new LambdaQueryWrapper<CallQueue>().in(CallQueue::getSkillGroupId, skillGroupIds))) {
            queue.setSyncStatus("NOT_SYNCED");
            queue.setSyncError(null);
            callQueueMapper.updateById(queue);
        }
    }
}
