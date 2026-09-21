package org.dromara.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.CallQueue;
import org.dromara.agent.domain.SkillGroupMember;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.mapper.CallQueueMapper;
import org.dromara.agent.mapper.SkillGroupMemberMapper;
import org.dromara.common.core.domain.dto.RoleDTO;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the current user's role data scope to call-center ownership ids.
 */
@Service
@RequiredArgsConstructor
public class AgentDataScopeService {
    private final AgentMapper agentMapper;
    private final SkillGroupMemberMapper skillGroupMemberMapper;
    private final CallQueueMapper callQueueMapper;

    public Scope current() {
        if (!LoginHelper.isLogin() || LoginHelper.isSuperAdmin() || LoginHelper.isTenantAdmin() || hasAllDataScope()) {
            return Scope.unrestricted();
        }
        Set<Long> agentIds = new LinkedHashSet<>(agentMapper.selectDataScopeAgentIds());
        if (agentIds.isEmpty()) {
            return new Scope(true, Set.of(), Set.of(), Set.of());
        }
        Set<Long> skillGroupIds = skillGroupMemberMapper.selectList(new LambdaQueryWrapper<SkillGroupMember>()
                .in(SkillGroupMember::getAgentId, agentIds))
            .stream()
            .map(SkillGroupMember::getSkillGroupId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> queueIds = skillGroupIds.isEmpty()
            ? Set.of()
            : callQueueMapper.selectList(new LambdaQueryWrapper<CallQueue>()
                    .in(CallQueue::getSkillGroupId, skillGroupIds)
                    .eq(CallQueue::getEnabled, true))
                .stream()
                .map(CallQueue::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new Scope(true, Set.copyOf(agentIds), Set.copyOf(skillGroupIds), Set.copyOf(queueIds));
    }

    private boolean hasAllDataScope() {
        List<RoleDTO> roles = LoginHelper.getLoginUser().getRoles();
        return roles != null && roles.stream().anyMatch(role -> "1".equals(role.getDataScope()));
    }

    public record Scope(boolean restricted, Set<Long> agentIds, Set<Long> skillGroupIds, Set<Long> queueIds) {
        public static Scope unrestricted() {
            return new Scope(false, Set.of(), Set.of(), Set.of());
        }

        public boolean allows(Long agentId, Long queueId) {
            if (!restricted) return true;
            if (agentId != null) return agentIds.contains(agentId);
            return queueId != null && queueIds.contains(queueId);
        }
    }
}
