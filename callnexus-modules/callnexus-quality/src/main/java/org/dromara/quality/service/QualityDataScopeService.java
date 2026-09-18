package org.dromara.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.request.CallRecordPageQuery;
import org.dromara.common.core.domain.dto.RoleDTO;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.quality.domain.QualityTask;
import org.dromara.quality.mapper.QualityTaskMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class QualityDataScopeService {
    private final QualityTaskMapper taskMapper;

    public Scope current() {
        if (LoginHelper.isSuperAdmin() || LoginHelper.isTenantAdmin() || hasAllDataScope()) {
            return Scope.unrestricted();
        }
        Set<Long> agentIds = new LinkedHashSet<>(taskMapper.selectScopedAgentIds(TenantHelper.getTenantId()));
        Set<Long> queueIds = agentIds.isEmpty()
            ? Set.of()
            : new LinkedHashSet<>(taskMapper.selectQueueIdsByAgentIds(TenantHelper.getTenantId(), agentIds));
        return new Scope(true, agentIds, queueIds);
    }

    public void applyTaskScope(LambdaQueryWrapper<QualityTask> wrapper, Scope scope) {
        if (!scope.restricted()) return;
        if (scope.agentIds().isEmpty() && scope.queueIds().isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        wrapper.and(value -> {
            boolean hasAgentScope = !scope.agentIds().isEmpty();
            if (hasAgentScope) value.in(QualityTask::getAgentId, scope.agentIds());
            if (!scope.queueIds().isEmpty()) {
                value.or(hasAgentScope).and(queue -> queue.isNull(QualityTask::getAgentId)
                    .in(QualityTask::getQueueId, scope.queueIds()));
            }
        });
    }

    public void applyCallScope(CallRecordPageQuery query, Scope scope) {
        query.setDataScopeRestricted(scope.restricted());
        query.setDataScopeAgentIds(scope.agentIds());
        query.setDataScopeQueueIds(scope.queueIds());
    }

    public void assertAccessible(Long agentId, Long queueId) {
        assertAccessible(current(), agentId, queueId);
    }

    public void assertAccessible(Scope scope, Long agentId, Long queueId) {
        if (scope.allows(agentId, queueId)) return;
        throw new ServiceException("无权访问该坐席或队列范围内的质检数据");
    }

    private boolean hasAllDataScope() {
        List<RoleDTO> roles = LoginHelper.getLoginUser().getRoles();
        return roles != null && roles.stream().anyMatch(role -> "1".equals(role.getDataScope()));
    }

    public record Scope(boolean restricted, Set<Long> agentIds, Set<Long> queueIds) {
        private static Scope unrestricted() {
            return new Scope(false, Set.of(), Set.of());
        }

        public boolean allows(Long agentId, Long queueId) {
            if (!restricted) return true;
            if (agentId != null) return agentIds.contains(agentId);
            return queueId != null && queueIds.contains(queueId);
        }
    }
}
