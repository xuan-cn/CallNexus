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

        boolean retryResult = retryResultCodes(task).contains(suggestedResultCode);
        int maxRetryCount = task.getMaxRetryCount() == null ? DEFAULT_MAX_RETRY_COUNT : task.getMaxRetryCount();
        boolean retryLimitReached = Boolean.TRUE.equals(task.getAutoRetryEnabled()) && retryResult
            && member.getAttemptCount() != null && member.getAttemptCount() > maxRetryCount;
        boolean retry = Boolean.TRUE.equals(task.getAutoRetryEnabled()) && retryResult && !retryLimitReached;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRetryAt = retry ? now.plusMinutes(
            task.getRetryIntervalMinutes() == null ? DEFAULT_RETRY_INTERVAL_MINUTES : task.getRetryIntervalMinutes()) : null;
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

    private void completeTaskIfFinished(Long taskId) {
        long remaining = memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .in(OutboundMember::getStatus, "PENDING", "RETRY", "CLAIMED", "DIALING"));
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
}
