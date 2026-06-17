package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.agent.service.AgentAvailabilityQueryService;
import org.dromara.agent.service.model.AgentAvailability;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.service.OutboundBlacklistMemberSyncService;
import org.dromara.outbound.service.OutboundMaintenanceSchedulerService;
import org.dromara.outbound.service.OutboundTaskService;
import org.dromara.outbound.service.model.OutboundMaintenanceResult;
import org.dromara.system.callcenterconfig.service.CallCenterConfigService;
import org.springframework.stereotype.Service;
import org.redisson.api.RLock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundMaintenanceSchedulerServiceImpl implements OutboundMaintenanceSchedulerService {

    private static final int DEFAULT_CLAIM_LEASE_MINUTES = 15;

    private final OutboundTaskMapper taskMapper;
    private final OutboundMemberMapper memberMapper;
    private final OutboundTaskService taskService;
    private final OutboundBlacklistMemberSyncService blacklistMemberSyncService;
    private final AgentAvailabilityQueryService agentAvailabilityQueryService;
    private final CallCenterConfigService callCenterConfigService;

    @Override
    public OutboundMaintenanceResult execute() {
        List<String> tenantIds = TenantHelper.ignore(() -> taskMapper.selectList(
                new LambdaQueryWrapper<OutboundTask>().select(OutboundTask::getTenantId))
            .stream()
            .map(OutboundTask::getTenantId)
            .distinct()
            .toList());
        Counter total = new Counter();
        for (String tenantId : tenantIds) {
            TenantHelper.dynamic(tenantId, () -> maintainTenant(total));
        }
        OutboundMaintenanceResult result = new OutboundMaintenanceResult(
            tenantIds.size(), total.taskCount, total.recoveredMemberCount,
            total.reactivatedTaskCount, total.assignedDueRetryCount, total.restoredBlacklistMemberCount);
        log.info("外呼维护调度执行完成，{}", result.summary());
        return result;
    }

    private void maintainTenant(Counter total) {
        total.restoredBlacklistMemberCount += blacklistMemberSyncService.restoreExpired();
        List<OutboundTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<OutboundTask>()
            .ne(OutboundTask::getStatus, "DRAFT"));
        LocalDateTime now = LocalDateTime.now();
        for (OutboundTask task : tasks) {
            int recovered = taskService.recoverExpired(task.getId());
            long dueRetryCount = memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
                .eq(OutboundMember::getTaskId, task.getId())
                .eq(OutboundMember::getStatus, "RETRY")
                .and(time -> time.isNull(OutboundMember::getNextFollowUpAt)
                    .or().le(OutboundMember::getNextFollowUpAt, now)));
            int reactivated = dueRetryCount > 0
                ? taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
                    .eq(OutboundTask::getId, task.getId())
                    .eq(OutboundTask::getStatus, "COMPLETED")
                    .set(OutboundTask::getStatus, "RUNNING"))
                : 0;
            int assigned = assignDueRetry(task, now);
            String summary = "到期重呼=" + dueRetryCount + "，恢复异常名单=" + recovered
                + "，自动分配=" + assigned + (reactivated > 0 ? "，任务已重新激活" : "");
            taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
                .eq(OutboundTask::getId, task.getId())
                .set(OutboundTask::getLastScheduledAt, now)
                .set(OutboundTask::getLastScheduleSummary, summary));
            total.taskCount++;
            total.recoveredMemberCount += recovered;
            total.reactivatedTaskCount += reactivated;
            total.assignedDueRetryCount += assigned;
        }
    }

    private int assignDueRetry(OutboundTask task, LocalDateTime now) {
        if (!Boolean.TRUE.equals(task.getAutoAssignDueRetry()) || task.getRetryAssigneeAgentId() == null
            || (!"RUNNING".equals(task.getStatus()) && !"COMPLETED".equals(task.getStatus()))) {
            return 0;
        }
        RLock lock = RedisUtils.getClient().getLock("callnexus:outbound:auto-assign:"
            + TenantHelper.getTenantId() + ":" + task.getRetryAssigneeAgentId());
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
            return locked ? assignDueRetryLocked(task, now) : 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private int assignDueRetryLocked(OutboundTask task, LocalDateTime now) {
        AgentAvailability agent = agentAvailabilityQueryService.get(task.getRetryAssigneeAgentId());
        if (agent == null || !agent.enabled() || !agent.idle() || agent.userId() == null) {
            return 0;
        }
        long active = memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getClaimedUserId, agent.userId())
            .in(OutboundMember::getStatus, "CLAIMED", "DIALING"));
        if (active > 0) {
            return 0;
        }
        OutboundMember candidate = memberMapper.selectOne(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, task.getId())
            .eq(OutboundMember::getStatus, "RETRY")
            .and(time -> time.isNull(OutboundMember::getNextFollowUpAt)
                .or().le(OutboundMember::getNextFollowUpAt, now))
            .orderByAsc(OutboundMember::getNextFollowUpAt)
            .last("LIMIT 1"));
        if (candidate == null) {
            return 0;
        }
        return memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, candidate.getId())
            .eq(OutboundMember::getStatus, "RETRY")
            .set(OutboundMember::getStatus, "CLAIMED")
            .set(OutboundMember::getClaimedAgentId, agent.agentId())
            .set(OutboundMember::getClaimedUserId, agent.userId())
            .set(OutboundMember::getClaimedAt, now)
            .set(OutboundMember::getLeaseExpiresAt, now.plusMinutes(claimLeaseMinutes())));
    }

    private int claimLeaseMinutes() {
        Integer value = callCenterConfigService.getInt("outbound.claimLeaseMinutes");
        return value == null || value < 1 ? DEFAULT_CLAIM_LEASE_MINUTES : value;
    }

    private static final class Counter {
        private int taskCount;
        private int recoveredMemberCount;
        private int reactivatedTaskCount;
        private int assignedDueRetryCount;
        private int restoredBlacklistMemberCount;
    }
}
