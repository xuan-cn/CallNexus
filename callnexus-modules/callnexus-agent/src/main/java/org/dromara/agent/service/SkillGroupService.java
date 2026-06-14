package org.dromara.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.*;
import org.dromara.agent.domain.request.SkillGroupRequest;
import org.dromara.agent.domain.response.SkillGroupResponse;
import org.dromara.agent.mapper.*;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SkillGroupService {
    private final SkillGroupMapper groupMapper;
    private final SkillGroupMemberMapper memberMapper;
    private final AgentMapper agentMapper;
    private final CallQueueMapper queueMapper;

    public List<SkillGroupResponse> list() {
        return groupMapper.selectList(new LambdaQueryWrapper<SkillGroup>().orderByAsc(SkillGroup::getGroupCode))
            .stream().map(this::response).toList();
    }

    public SkillGroupResponse get(Long id) {
        return response(require(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(SkillGroupRequest request) {
        ensureCode(request.getGroupCode(), null);
        validateAgents(request.getAgentIds());
        SkillGroup group = new SkillGroup();
        apply(group, request);
        groupMapper.insert(group);
        replaceMembers(group.getId(), request.getAgentIds());
        return group.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SkillGroupRequest request) {
        ensureCode(request.getGroupCode(), id);
        validateAgents(request.getAgentIds());
        SkillGroup group = require(id);
        apply(group, request);
        group.setVersion(request.getVersion());
        if (groupMapper.updateById(group) != 1) {
            throw new ServiceException("技能组已被其他用户修改，请刷新后重试");
        }
        replaceMembers(id, request.getAgentIds());
        markQueuesNotSynced(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        require(id);
        if (queueMapper.exists(new LambdaQueryWrapper<CallQueue>().eq(CallQueue::getSkillGroupId, id))) {
            throw new ServiceException("技能组已被呼叫队列引用，无法删除");
        }
        memberMapper.deletePhysicallyByGroupId(LoginHelper.getTenantId(), id);
        groupMapper.deleteById(id);
    }

    private void validateAgents(List<Long> agentIds) {
        long distinctCount = agentIds.stream().distinct().count();
        long enabledCount = agentMapper.selectCount(new LambdaQueryWrapper<Agent>()
            .in(Agent::getId, agentIds)
            .eq(Agent::getEnabled, true));
        if (enabledCount != distinctCount) {
            throw new ServiceException("技能组包含不存在或已停用的坐席");
        }
    }

    private void replaceMembers(Long groupId, List<Long> agentIds) {
        memberMapper.deletePhysicallyByGroupId(LoginHelper.getTenantId(), groupId);
        for (Long agentId : agentIds.stream().distinct().toList()) {
            SkillGroupMember member = new SkillGroupMember();
            member.setSkillGroupId(groupId);
            member.setAgentId(agentId);
            member.setSkillLevel(1);
            member.setPriority(1);
            memberMapper.insert(member);
        }
    }

    private List<Long> memberIds(Long groupId) {
        return memberMapper.selectList(new LambdaQueryWrapper<SkillGroupMember>()
                .eq(SkillGroupMember::getSkillGroupId, groupId)
                .orderByAsc(SkillGroupMember::getPriority))
            .stream().map(SkillGroupMember::getAgentId).toList();
    }

    private SkillGroup require(Long id) {
        SkillGroup group = groupMapper.selectById(id);
        if (group == null) {
            throw new ServiceException("技能组不存在");
        }
        return group;
    }

    private void ensureCode(String code, Long excludedId) {
        if (groupMapper.exists(new LambdaQueryWrapper<SkillGroup>()
            .eq(SkillGroup::getGroupCode, code)
            .ne(excludedId != null, SkillGroup::getId, excludedId))) {
            throw new ServiceException("技能组编码已存在");
        }
    }

    private void apply(SkillGroup group, SkillGroupRequest request) {
        group.setGroupCode(request.getGroupCode());
        group.setGroupName(request.getGroupName());
        group.setEnabled(request.getEnabled());
        group.setRemark(request.getRemark());
    }

    private SkillGroupResponse response(SkillGroup group) {
        SkillGroupResponse response = new SkillGroupResponse();
        response.setId(group.getId());
        response.setGroupCode(group.getGroupCode());
        response.setGroupName(group.getGroupName());
        response.setAgentIds(memberIds(group.getId()));
        response.setMemberCount(response.getAgentIds().size());
        response.setEnabled(group.getEnabled());
        response.setRemark(group.getRemark());
        response.setVersion(group.getVersion());
        response.setCreateTime(group.getCreateTime());
        return response;
    }

    private void markQueuesNotSynced(Long skillGroupId) {
        for (CallQueue queue : queueMapper.selectList(new LambdaQueryWrapper<CallQueue>().eq(CallQueue::getSkillGroupId, skillGroupId))) {
            queue.setSyncStatus("NOT_SYNCED");
            queue.setSyncError(null);
            queueMapper.updateById(queue);
        }
    }
}
