package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiTicketDraftTask;
import org.dromara.ai.domain.AiTicketDraft;
import org.dromara.ai.mapper.AiTicketDraftMapper;
import org.dromara.ai.mapper.AiTicketDraftTaskMapper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiTicketDraftTaskDispatcher {
    private final AiTicketDraftTaskMapper taskMapper;
    private final AiTicketDraftMapper draftMapper;
    private final AiTicketDraftGenerator generator;
    private final ScheduledExecutorService scheduledExecutorService;
    @Resource(name = "aiTicketTaskExecutor")
    private Executor executor;
    private final String leaseOwner = UUID.randomUUID().toString();

    @PostConstruct
    public void scheduleRecovery() {
        scheduledExecutorService.scheduleWithFixedDelay(this::scanSafely, 15, 15, TimeUnit.SECONDS);
    }

    public void dispatchAfterCommit(Long taskId, String tenantId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch(taskId, tenantId); }
            });
        } else {
            dispatch(taskId, tenantId);
        }
    }

    public void dispatch(Long taskId, String tenantId) {
        executor.execute(() -> TenantHelper.dynamic(tenantId, () -> process(taskId)));
    }

    public void dispatchAt(Long taskId, String tenantId, LocalDateTime executeAt) {
        long delay = executeAt == null ? 0L
            : Math.max(0L, Duration.between(LocalDateTime.now(), executeAt).toMillis());
        scheduledExecutorService.schedule(() -> TenantHelper.dynamic(tenantId,
            () -> activateRealtimeTask(taskId, tenantId)), delay, TimeUnit.MILLISECONDS);
    }

    private void scanSafely() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<AiTicketDraftTask> tasks = TenantHelper.ignore(() -> taskMapper.selectList(
                new LambdaQueryWrapper<AiTicketDraftTask>()
                    .and(value -> value.eq(AiTicketDraftTask::getStatus, "READY")
                        .or(retry -> retry.eq(AiTicketDraftTask::getStatus, "RETRY").le(AiTicketDraftTask::getNextRetryAt, now))
                        .or(processing -> processing.eq(AiTicketDraftTask::getStatus, "PROCESSING")
                            .le(AiTicketDraftTask::getLeaseExpiresAt, now)))
                    .orderByAsc(AiTicketDraftTask::getCreateTime).last("LIMIT 20")));
            tasks.forEach(task -> dispatch(task.getId(), task.getTenantId()));
        } catch (Exception exception) {
            log.error("扫描 AI 工单草稿任务失败", exception);
        }
    }

    private void process(Long taskId) {
        if (!claim(taskId)) return;
        AiTicketDraftTask task = taskMapper.selectById(taskId);
        try {
            generator.generate(task);
            AiTicketDraftTask latest = taskMapper.selectById(taskId);
            boolean dirtiedWhileProcessing = latest != null && (
                latest.getNextRetryAt() != null && task.getStartedAt() != null
                    && latest.getNextRetryAt().isAfter(task.getStartedAt())
                || !Objects.equals(latest.getTriggerType(), task.getTriggerType())
                || !Objects.equals(latest.getCallCompleted(), task.getCallCompleted()));
            if (dirtiedWhileProcessing) {
                latest.setStatus("WAITING");
                if (latest.getNextRetryAt() == null) latest.setNextRetryAt(LocalDateTime.now());
                latest.setLeaseOwner(null);
                latest.setLeaseExpiresAt(null);
                taskMapper.updateById(latest);
                dispatchAt(taskId, latest.getTenantId(), latest.getNextRetryAt());
                return;
            }
            task.setStatus("SUCCESS");
            task.setCompletedAt(LocalDateTime.now());
            task.setNextRetryAt(null);
            task.setLeaseOwner(null);
            task.setLeaseExpiresAt(null);
            taskMapper.updateById(task);
        } catch (AiTicketDraftGenerator.SkipGenerationException exception) {
            task.setStatus("SKIPPED");
            task.setFailureReason(exception.getMessage());
            task.setCompletedAt(LocalDateTime.now());
            task.setLeaseOwner(null);
            task.setLeaseExpiresAt(null);
            taskMapper.updateById(task);
            markRegenerationFailed(task, exception.getMessage());
        } catch (Exception exception) {
            int retry = task.getRetryCount() == null ? 1 : task.getRetryCount() + 1;
            task.setRetryCount(retry);
            task.setStatus(retry >= 3 ? "FAILED" : "RETRY");
            task.setNextRetryAt(retry >= 3 ? null : LocalDateTime.now().plusMinutes(retry));
            task.setFailureReason(limit(exception.getMessage()));
            task.setLeaseOwner(null);
            task.setLeaseExpiresAt(null);
            if (retry >= 3) task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            if (retry >= 3) markRegenerationFailed(task, exception.getMessage());
            log.warn("AI 工单草稿生成失败，taskId={}，retry={}", taskId, retry, exception);
        }
    }

    private boolean claim(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.update(null, new LambdaUpdateWrapper<AiTicketDraftTask>()
            .eq(AiTicketDraftTask::getId, taskId)
            .and(value -> value.eq(AiTicketDraftTask::getStatus, "READY")
                .or(retry -> retry.eq(AiTicketDraftTask::getStatus, "RETRY").le(AiTicketDraftTask::getNextRetryAt, now))
                .or(processing -> processing.eq(AiTicketDraftTask::getStatus, "PROCESSING")
                    .le(AiTicketDraftTask::getLeaseExpiresAt, now)))
            .set(AiTicketDraftTask::getStatus, "PROCESSING")
            .set(AiTicketDraftTask::getLeaseOwner, leaseOwner)
            .set(AiTicketDraftTask::getLeaseExpiresAt, now.plusMinutes(5))
            .set(AiTicketDraftTask::getStartedAt, now)
            .set(AiTicketDraftTask::getFailureReason, null)) == 1;
    }

    private void activateRealtimeTask(Long taskId, String tenantId) {
        AiTicketDraftTask task = taskMapper.selectById(taskId);
        if (task == null || task.getNextRetryAt() == null || "PROCESSING".equals(task.getStatus())) return;
        if (task.getNextRetryAt().isAfter(LocalDateTime.now())) {
            dispatchAt(taskId, tenantId, task.getNextRetryAt());
            return;
        }
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<AiTicketDraftTask>()
            .eq(AiTicketDraftTask::getId, taskId)
            .ne(AiTicketDraftTask::getStatus, "PROCESSING")
            .set(AiTicketDraftTask::getStatus, "READY"));
        if (updated == 1) dispatch(taskId, tenantId);
    }

    private String limit(String message) {
        if (message == null) return "未知错误";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private void markRegenerationFailed(AiTicketDraftTask task, String reason) {
        draftMapper.update(null, new LambdaUpdateWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getPolicyId, task.getPolicyId())
            .eq(AiTicketDraft::getSourceCallId, task.getBusinessCallId())
            .eq(AiTicketDraft::getStatus, "GENERATING")
            .set(AiTicketDraft::getStatus, "FAILED")
            .set(AiTicketDraft::getFailureReason, limit(reason))
            .setSql("version = version + 1"));
    }
}
