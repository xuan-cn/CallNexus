package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.domain.OutboundTaskRetryRule;
import org.dromara.outbound.mapper.OutboundTaskRetryRuleMapper;
import org.dromara.outbound.service.OutboundAutomaticRetryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundAutomaticRetryServiceImpl implements OutboundAutomaticRetryService {
    private static final int DEFAULT_MAX_RETRY_COUNT = 2;
    private static final int DEFAULT_RETRY_INTERVAL_MINUTES = 30;
    private static final String DEFAULT_RETRY_RESULT_CODES = "NO_ANSWER,BUSY,OTHER";

    private final OutboundTaskMapper taskMapper;
    private final OutboundMemberMapper memberMapper;
    private final OutboundTaskRetryRuleMapper retryRuleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applySystemSuggestion(Long memberId, String businessCallId, String suggestedResultCode) {
        OutboundMember member = memberMapper.selectById(memberId);
        if (member == null || !"DIALING".equals(member.getStatus())
            || !businessCallId.equals(member.getBusinessCallId())) {
            return;
        }
        OutboundTask task = taskMapper.selectById(member.getTaskId());
        if (task == null) return;

        RetryPolicy policy = retryPolicy(task, suggestedResultCode);
        boolean retryResult = policy.enabled();
        int maxRetryCount = policy.maxRetryCount();
        boolean retryLimitReached = retryResult
            && member.getAttemptCount() != null && member.getAttemptCount() > maxRetryCount;
        boolean retry = retryResult && !retryLimitReached;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRetryAt = retry ? now.plusMinutes(
            policy.retryIntervalMinutes()) : null;
        String completionReason = retry ? null : retryLimitReached ? "RETRY_LIMIT_REACHED" : "SYSTEM";
        String resultRemark = retry
            ? "系统根据通话结果自动安排重呼"
            : retryLimitReached ? "已达到任务最大自动重呼次数" : "系统根据通话结果自动完成";

        int updated = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, memberId)
            .eq(OutboundMember::getStatus, "DIALING")
            .eq(OutboundMember::getBusinessCallId, businessCallId)
            .set(OutboundMember::getStatus, retry ? "RETRY" : "COMPLETED")
            .set(OutboundMember::getResultCode, suggestedResultCode)
            .set(OutboundMember::getResultRemark, resultRemark)
            .set(OutboundMember::getNextFollowUpAt, nextRetryAt)
            .set(OutboundMember::getLeaseExpiresAt, null)
            .set(OutboundMember::getCompletedAt, retry ? null : now)
            .set(OutboundMember::getCompletionReason, completionReason));
        if (updated == 0) return;

        if (retry) {
            reactivateCompletedTask(task.getId());
        } else {
            completeTaskIfFinished(task.getId());
        }
        log.info("外呼名单已按系统建议自动流转，taskId={}，memberId={}，result={}，nextStatus={}，nextRetryAt={}",
            task.getId(), memberId, suggestedResultCode, retry ? "RETRY" : "COMPLETED", nextRetryAt);
    }

    private Set<String> retryResultCodes(OutboundTask task) {
        String configured = StringUtils.isBlank(task.getRetryResultCodes())
            ? DEFAULT_RETRY_RESULT_CODES : task.getRetryResultCodes();
        return Arrays.stream(configured.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toSet());
    }

    private RetryPolicy retryPolicy(OutboundTask task, String resultCode) {
        if ("AUTO".equals(task.getTaskType())) {
            OutboundTaskRetryRule rule = retryRuleMapper.selectOne(new LambdaQueryWrapper<OutboundTaskRetryRule>()
                .eq(OutboundTaskRetryRule::getTaskId, task.getId())
                .eq(OutboundTaskRetryRule::getResultCode, resultCode)
                .eq(OutboundTaskRetryRule::getRetryEnabled, true)
                .last("LIMIT 1"));
            if (rule == null) return new RetryPolicy(false, 0, DEFAULT_RETRY_INTERVAL_MINUTES);
            return new RetryPolicy(true,
                rule.getMaxRetryCount() == null ? 1 : rule.getMaxRetryCount(),
                rule.getRetryIntervalMinutes() == null ? DEFAULT_RETRY_INTERVAL_MINUTES : rule.getRetryIntervalMinutes());
        }
        boolean enabled = Boolean.TRUE.equals(task.getAutoRetryEnabled()) && retryResultCodes(task).contains(resultCode);
        return new RetryPolicy(enabled,
            task.getMaxRetryCount() == null ? DEFAULT_MAX_RETRY_COUNT : task.getMaxRetryCount(),
            task.getRetryIntervalMinutes() == null ? DEFAULT_RETRY_INTERVAL_MINUTES : task.getRetryIntervalMinutes());
    }

    private void completeTaskIfFinished(Long taskId) {
        long remaining = memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .in(OutboundMember::getStatus, "PENDING", "RETRY", "SCHEDULED", "CLAIMED", "DIALING"));
        if (remaining == 0) {
            taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
                .eq(OutboundTask::getId, taskId)
                .set(OutboundTask::getStatus, "COMPLETED"));
        }
    }

    private void reactivateCompletedTask(Long taskId) {
        taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
            .eq(OutboundTask::getId, taskId)
            .eq(OutboundTask::getStatus, "COMPLETED")
            .set(OutboundTask::getStatus, "RUNNING"));
    }

    private record RetryPolicy(boolean enabled, int maxRetryCount, int retryIntervalMinutes) {
    }
}
