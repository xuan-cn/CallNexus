package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.outbound.domain.AutoOutboundDispatch;
import org.dromara.outbound.domain.OutboundAttempt;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.OutboundTaskCallWindow;
import org.dromara.outbound.mapper.AutoOutboundDispatchMapper;
import org.dromara.outbound.mapper.OutboundAttemptMapper;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskCallWindowMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.service.AutoOutboundSchedulerService;
import org.dromara.outbound.service.model.AutoOutboundSchedulerResult;
import org.dromara.resource.phone.domain.response.PhoneNumberResponse;
import org.dromara.resource.phone.service.PhoneNumberApplicationService;
import org.dromara.system.callcenterconfig.service.CallCenterConfigService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoOutboundSchedulerServiceImpl implements AutoOutboundSchedulerService {

    private static final int DEFAULT_TASK_LEASE_SECONDS = 15;
    private static final int DEFAULT_DISPATCH_LEASE_MINUTES = 10;
    private static final int DEFAULT_TENANT_CONCURRENCY = 100;
    private static final int DEFAULT_CALLER_CONCURRENCY = 20;
    private static final int DEFAULT_NODE_CONCURRENCY = 100;
    private static final int MAX_CANDIDATES_PER_SCAN = 200;

    private final OutboundTaskMapper taskMapper;
    private final OutboundMemberMapper memberMapper;
    private final OutboundAttemptMapper attemptMapper;
    private final OutboundTaskCallWindowMapper callWindowMapper;
    private final AutoOutboundDispatchMapper dispatchMapper;
    private final AutoOutboundDispatchClaimService claimService;
    private final CallCenterConfigService configService;
    private final PhoneNumberApplicationService phoneNumberService;

    private final String schedulerOwner = ManagementFactory.getRuntimeMXBean().getName() + "-"
        + UUID.randomUUID().toString().substring(0, 8);

    @Override
    public AutoOutboundSchedulerResult execute() {
        List<String> tenantIds = TenantHelper.ignore(() -> taskMapper.selectList(
                new LambdaQueryWrapper<OutboundTask>()
                    .select(OutboundTask::getTenantId)
                    .eq(OutboundTask::getTaskType, "AUTO")
                    .eq(OutboundTask::getStatus, "RUNNING"))
            .stream().map(OutboundTask::getTenantId).distinct().toList());
        Counter total = new Counter();
        for (String tenantId : tenantIds) {
            TenantHelper.dynamic(tenantId, () -> scheduleTenant(total));
        }
        AutoOutboundSchedulerResult result = new AutoOutboundSchedulerResult(
            tenantIds.size(), total.scanned, total.leased, total.scheduled, total.recovered, total.completed);
        log.info("自动外呼调度执行完成，owner={}，{}", schedulerOwner, result.summary());
        return result;
    }

    private void scheduleTenant(Counter total) {
        LocalDateTime now = LocalDateTime.now();
        total.recovered += dispatchMapper.recoverExpired(TenantHelper.getTenantId(), now);
        List<OutboundTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<OutboundTask>()
            .eq(OutboundTask::getTaskType, "AUTO")
            .eq(OutboundTask::getStatus, "RUNNING")
            .orderByAsc(OutboundTask::getLastScheduledAt)
            .orderByAsc(OutboundTask::getId));
        for (OutboundTask task : tasks) {
            total.scanned++;
            if (taskMapper.acquireSchedulerLease(task.getId(), task.getTenantId(), schedulerOwner, now,
                now.plusSeconds(configInt("autoOutbound.taskLeaseSeconds", DEFAULT_TASK_LEASE_SECONDS))) == 0) {
                continue;
            }
            total.leased++;
            scheduleTask(task, now, total);
        }
    }

    private void scheduleTask(OutboundTask task, LocalDateTime now, Counter total) {
        if (!insideCallWindow(task)) {
            updateSummary(task.getId(), now, "当前不在允许呼叫时段");
            return;
        }
        long taskActive = countTaskActive(task.getId());
        int taskSlots = Math.max(0, value(task.getConcurrencyLimit(), 1) - Math.toIntExact(taskActive));
        int tenantSlots = Math.max(0, configInt("autoOutbound.tenantConcurrencyLimit", DEFAULT_TENANT_CONCURRENCY)
            - Math.toIntExact(dispatchMapper.countTenantActive(task.getTenantId())));
        int callerSlots = task.getCallerNumberId() == null ? taskSlots : Math.max(0,
            configInt("autoOutbound.callerConcurrencyLimit", DEFAULT_CALLER_CONCURRENCY)
                - Math.toIntExact(dispatchMapper.countCallerActive(task.getTenantId(), task.getCallerNumberId())));
        PhoneNumberResponse callerNumber = task.getCallerNumberId() == null ? null
            : phoneNumberService.get(task.getCallerNumberId());
        int nodeSlots = callerNumber == null || callerNumber.getNodeId() == null ? taskSlots : Math.max(0,
            configInt("autoOutbound.nodeConcurrencyLimit", DEFAULT_NODE_CONCURRENCY)
                - Math.toIntExact(dispatchMapper.countNodeActive(task.getTenantId(), callerNumber.getNodeId())));
        LocalDateTime minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        long minuteCount = dispatchMapper.selectCount(new LambdaQueryWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getTaskId, task.getId())
            .ge(AutoOutboundDispatch::getScheduledAt, minuteStart));
        int rateSlots = Math.max(0, value(task.getCallsPerMinute(), 1) - Math.toIntExact(minuteCount));
        int limit = Math.min(Math.min(taskSlots, tenantSlots), Math.min(nodeSlots, Math.min(callerSlots, rateSlots)));
        if (limit <= 0) {
            updateSummary(task.getId(), now, "等待容量：任务并发=" + taskActive + "，本分钟已调度=" + minuteCount);
            return;
        }

        List<OutboundMember> candidates = memberMapper.selectList(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, task.getId())
            .and(status -> status.eq(OutboundMember::getStatus, "PENDING")
                .or(retry -> retry.eq(OutboundMember::getStatus, "RETRY")
                    .and(due -> due.isNull(OutboundMember::getNextFollowUpAt)
                        .or().le(OutboundMember::getNextFollowUpAt, now))))
            .orderByAsc(OutboundMember::getNextFollowUpAt)
            .orderByAsc(OutboundMember::getCreateTime)
            .last("LIMIT " + Math.min(MAX_CANDIDATES_PER_SCAN, Math.max(limit * 4, limit))));
        int scheduled = 0;
        for (OutboundMember member : candidates) {
            if (scheduled >= limit) {
                break;
            }
            Eligibility eligibility = eligible(task, member, now);
            if (eligibility.permanentlyBlocked()) {
                memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
                    .eq(OutboundMember::getId, member.getId())
                    .in(OutboundMember::getStatus, "PENDING", "RETRY")
                    .set(OutboundMember::getStatus, "SKIPPED")
                    .set(OutboundMember::getCompletionReason, eligibility.reason())
                    .set(OutboundMember::getCompletedAt, now));
                continue;
            }
            if (!eligibility.allowed()) {
                continue;
            }
            try {
                if (claimService.claim(task, member, now,
                    configInt("autoOutbound.dispatchLeaseMinutes", DEFAULT_DISPATCH_LEASE_MINUTES))) {
                    scheduled++;
                }
            } catch (DuplicateKeyException exception) {
                log.debug("自动外呼调度幂等冲突，taskId={}，memberId={}", task.getId(), member.getId());
            }
        }
        total.scheduled += scheduled;
        long pending = countPending(task.getId());
        long active = countTaskActive(task.getId());
        if (pending == 0 && active == 0) {
            int completed = taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
                .eq(OutboundTask::getId, task.getId())
                .eq(OutboundTask::getStatus, "RUNNING")
                .set(OutboundTask::getStatus, "COMPLETED")
                .set(OutboundTask::getLastScheduledAt, now)
                .set(OutboundTask::getLastScheduleSummary, "名单已全部处理完成"));
            total.completed += completed;
            return;
        }
        updateSummary(task.getId(), now, "生成调度单=" + scheduled + "，待调度=" + pending + "，活动=" + active);
    }

    private Eligibility eligible(OutboundTask task, OutboundMember member, LocalDateTime now) {
        int attemptCount = value(member.getAttemptCount(), 0);
        if (attemptCount >= value(task.getMaxCallsTotal(), 1)) {
            return Eligibility.blocked("已达到任务最大呼叫次数");
        }
        ZoneId taskZone = ZoneId.of(task.getScheduleTimezone());
        ZonedDateTime zonedNow = ZonedDateTime.now(taskZone);
        LocalDateTime dayStart = zonedNow.toLocalDate().atStartOfDay(taskZone)
            .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        long todayAttempts = attemptMapper.selectCount(new LambdaQueryWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getTaskId, task.getId())
            .eq(OutboundAttempt::getMemberId, member.getId())
            .ge(OutboundAttempt::getStartedAt, dayStart)
            .lt(OutboundAttempt::getStartedAt, dayEnd));
        if (todayAttempts >= value(task.getMaxCallsPerDay(), 1)) {
            return Eligibility.waiting("今日呼叫次数已达上限");
        }
        OutboundAttempt lastAttempt = attemptMapper.selectOne(new LambdaQueryWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getTaskId, task.getId())
            .eq(OutboundAttempt::getMemberId, member.getId())
            .isNotNull(OutboundAttempt::getStartedAt)
            .orderByDesc(OutboundAttempt::getStartedAt)
            .last("LIMIT 1"));
        if (lastAttempt != null && lastAttempt.getStartedAt().plusMinutes(
            value(task.getMinCallIntervalMinutes(), 0)).isAfter(now)) {
            return Eligibility.waiting("尚未达到最小呼叫间隔");
        }
        return Eligibility.allowedResult();
    }

    private boolean insideCallWindow(OutboundTask task) {
        ZoneId zone = ZoneId.of(task.getScheduleTimezone());
        ZonedDateTime now = ZonedDateTime.now(zone);
        int weekday = weekday(now.getDayOfWeek());
        LocalTime time = now.toLocalTime();
        return callWindowMapper.selectList(new LambdaQueryWrapper<OutboundTaskCallWindow>()
                .eq(OutboundTaskCallWindow::getTaskId, task.getId())
                .eq(OutboundTaskCallWindow::getEnabled, true))
            .stream().anyMatch(window -> weekdays(window.getWeekdays()).contains(weekday)
                && !time.isBefore(window.getStartTime()) && time.isBefore(window.getEndTime()));
    }

    private Set<Integer> weekdays(String value) {
        return Arrays.stream(value.split(",")).filter(item -> !item.isBlank())
            .map(Integer::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    private int weekday(DayOfWeek dayOfWeek) {
        return dayOfWeek.getValue();
    }

    private long countTaskActive(Long taskId) {
        return dispatchMapper.selectCount(new LambdaQueryWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getTaskId, taskId)
            .in(AutoOutboundDispatch::getStatus, "READY", "PROCESSING"));
    }

    private long countPending(Long taskId) {
        return memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .in(OutboundMember::getStatus, "PENDING", "RETRY"));
    }

    private void updateSummary(Long taskId, LocalDateTime now, String summary) {
        taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
            .eq(OutboundTask::getId, taskId)
            .eq(OutboundTask::getStatus, "RUNNING")
            .set(OutboundTask::getLastScheduledAt, now)
            .set(OutboundTask::getLastScheduleSummary, summary)
            .set(OutboundTask::getSchedulerHeartbeatAt, now));
    }

    private int configInt(String key, int defaultValue) {
        Integer value = configService.getIntOrDefault(key, defaultValue);
        return value == null || value < 1 ? defaultValue : value;
    }

    private int value(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private record Eligibility(boolean allowed, boolean permanentlyBlocked, String reason) {
        private static Eligibility allowedResult() {
            return new Eligibility(true, false, null);
        }

        private static Eligibility waiting(String reason) {
            return new Eligibility(false, false, reason);
        }

        private static Eligibility blocked(String reason) {
            return new Eligibility(false, true, reason);
        }
    }

    private static final class Counter {
        private int scanned;
        private int leased;
        private int scheduled;
        private int recovered;
        private int completed;
    }
}
