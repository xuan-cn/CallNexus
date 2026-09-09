package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.CallQueue;
import org.dromara.agent.domain.SkillGroup;
import org.dromara.agent.domain.SkillGroupMember;
import org.dromara.agent.mapper.CallQueueMapper;
import org.dromara.agent.mapper.SkillGroupMapper;
import org.dromara.agent.mapper.SkillGroupMemberMapper;
import org.dromara.ai.service.AiAgentAssistAvailabilityProvider;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CallAgentAssistAvailabilityProvider implements AiAgentAssistAvailabilityProvider {
    private final CallSessionMapper callSessionMapper;
    private final CallQueueMapper callQueueMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final SkillGroupMemberMapper skillGroupMemberMapper;

    @Override
    public Availability resolve(String businessCallId) {
        CallSession callSession = callSessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .orderByDesc(CallSession::getId).last("limit 1"));
        if (callSession == null) return Availability.disabled();
        Long agentId = callSession.getOwnerAgentId() != null
            ? callSession.getOwnerAgentId() : callSession.getAgentId();
        SkillGroup group = resolveGroup(callSession.getHandlingQueueId(), agentId);
        return group == null ? Availability.disabled()
            : new Availability(true, callSession.getId(), agentId, group.getId(), group.getAssistAgentId());
    }

    private SkillGroup resolveGroup(Long queueId, Long agentId) {
        if (queueId != null) {
            CallQueue queue = callQueueMapper.selectById(queueId);
            if (queue != null && queue.getSkillGroupId() != null) {
                SkillGroup group = skillGroupMapper.selectById(queue.getSkillGroupId());
                if (enabled(group)) return group;
            }
        }
        if (agentId == null) return null;
        List<SkillGroupMember> memberships = skillGroupMemberMapper.selectList(
            new LambdaQueryWrapper<SkillGroupMember>()
                .eq(SkillGroupMember::getAgentId, agentId)
                .orderByAsc(SkillGroupMember::getPriority, SkillGroupMember::getId));
        for (SkillGroupMember membership : memberships) {
            SkillGroup group = skillGroupMapper.selectById(membership.getSkillGroupId());
            if (enabled(group)) return group;
        }
        return null;
    }

    private boolean enabled(SkillGroup group) {
        return group != null && Boolean.TRUE.equals(group.getEnabled())
            && Boolean.TRUE.equals(group.getAssistEnabled()) && group.getAssistAgentId() != null;
    }
}
