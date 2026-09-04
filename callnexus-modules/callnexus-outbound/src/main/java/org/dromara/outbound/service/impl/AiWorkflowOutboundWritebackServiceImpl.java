package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.workflow.AiWorkflowOutboundWritebackService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.outbound.domain.OutboundAttempt;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.request.OutboundBlacklistRequest;
import org.dromara.outbound.mapper.OutboundAttemptMapper;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.service.OutboundBlacklistService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiWorkflowOutboundWritebackServiceImpl implements AiWorkflowOutboundWritebackService {
    private static final Set<String> RESULT_CODES = Set.of(
        "INTERESTED", "NOT_INTERESTED", "CALLBACK_REQUESTED", "TRANSFERRED",
        "NO_INPUT", "ASR_UNRECOGNIZED", "DO_NOT_CALL", "PENDING_REVIEW", "WORKFLOW_FAILED"
    );
    private static final int DEFAULT_CALLBACK_DELAY_MINUTES = 30;

    private final OutboundTaskMapper taskMapper;
    private final OutboundMemberMapper memberMapper;
    private final OutboundAttemptMapper attemptMapper;
    private final CustomerApplicationService customerService;
    private final OutboundBlacklistService blacklistService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void writeBack(Long taskId, Long memberId, String businessCallId, String resultCode) {
        if (!RESULT_CODES.contains(resultCode)) {
            throw new ServiceException("AI 工作流外呼结果无效：" + resultCode);
        }
        OutboundTask task = taskMapper.selectById(taskId);
        OutboundMember member = memberMapper.selectById(memberId);
        if (task == null || member == null || !taskId.equals(member.getTaskId())) {
            throw new ServiceException("AI 工作流对应的外呼任务或名单不存在");
        }
        if (!businessCallId.equals(member.getBusinessCallId())) {
            throw new ServiceException("AI 工作流通话与外呼名单不匹配");
        }
        if (!"DIALING".equals(member.getStatus())) {
            if (resultCode.equals(member.getResultCode())) {
                return;
            }
            throw new ServiceException("外呼名单已被其他流程回写，请刷新后重试");
        }

        boolean callback = "CALLBACK_REQUESTED".equals(resultCode);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextFollowUpAt = callback ? now.plusMinutes(DEFAULT_CALLBACK_DELAY_MINUTES) : null;
        String resultRemark = "AI 工作流回写：" + resultLabel(resultCode);
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, memberId)
            .eq(OutboundMember::getTaskId, taskId)
            .eq(OutboundMember::getBusinessCallId, businessCallId)
            .eq(OutboundMember::getStatus, "DIALING")
            .set(OutboundMember::getStatus, callback ? "RETRY" : "COMPLETED")
            .set(OutboundMember::getResultCode, resultCode)
            .set(OutboundMember::getResultRemark, resultRemark)
            .set(OutboundMember::getNextFollowUpAt, nextFollowUpAt)
            .set(OutboundMember::getLeaseExpiresAt, null)
            .set(OutboundMember::getCompletedAt, callback ? null : now)
            .set(OutboundMember::getCompletionReason, callback ? null : "AI_WORKFLOW"));
        if (updated == 0) {
            throw new ServiceException("外呼名单状态已变化，AI 工作流回写失败");
        }

        OutboundAttempt attempt = attemptMapper.selectOne(new LambdaQueryWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getMemberId, memberId)
            .eq(OutboundAttempt::getBusinessCallId, businessCallId)
            .last("LIMIT 1"));
        if (attempt != null) {
            attemptMapper.update(null, new LambdaUpdateWrapper<OutboundAttempt>()
                .eq(OutboundAttempt::getId, attempt.getId())
                .set(OutboundAttempt::getResultCode, resultCode)
                .set(OutboundAttempt::getResultRemark, resultRemark));
        }
        if (member.getCustomerId() != null && Boolean.TRUE.equals(task.getResultWritebackEnabled())) {
            String content = "自动外呼任务“" + task.getTaskName() + "”由 AI 工作流判定为：" + resultLabel(resultCode)
                + "，通话ID：" + businessCallId
                + (callback ? "，预计30分钟后回访" : "");
            customerService.recordOutboundResult(member.getCustomerId(), attempt == null ? null : attempt.getId(),
                content, resultTag(task, resultCode));
        }
        if ("DO_NOT_CALL".equals(resultCode)) {
            addDoNotCall(member, taskId);
        }
        if (callback) {
            taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
                .eq(OutboundTask::getId, taskId)
                .eq(OutboundTask::getStatus, "COMPLETED")
                .set(OutboundTask::getStatus, "RUNNING"));
        } else {
            completeTaskIfFinished(taskId);
        }
        log.info("AI 工作流外呼结果已回写，taskId={}，memberId={}，businessCallId={}，resultCode={}，nextFollowUpAt={}",
            taskId, memberId, businessCallId, resultCode, nextFollowUpAt);
    }

    private void addDoNotCall(OutboundMember member, Long taskId) {
        OutboundBlacklistRequest request = new OutboundBlacklistRequest();
        request.setScopeType("GLOBAL");
        request.setTaskId(taskId);
        request.setPhoneNumber(member.getPhoneNumber());
        request.setReason("客户在 AI 自动外呼中明确拒绝联系");
        request.setSource("CUSTOMER_REQUEST");
        request.setEffectiveAt(LocalDateTime.now());
        request.setEnabled(true);
        try {
            blacklistService.create(request);
        } catch (ServiceException exception) {
            if (exception.getMessage() == null || !exception.getMessage().contains("相同电话号码")) {
                throw exception;
            }
        }
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

    private String resultTag(OutboundTask task, String resultCode) {
        return Set.of("INTERESTED", "TRANSFERRED").contains(resultCode)
            ? task.getConnectedTag() : task.getFailedTag();
    }

    private String resultLabel(String resultCode) {
        return switch (resultCode) {
            case "INTERESTED" -> "有意向";
            case "NOT_INTERESTED" -> "无意向";
            case "CALLBACK_REQUESTED" -> "要求回访";
            case "TRANSFERRED" -> "已转人工";
            case "NO_INPUT" -> "客户无输入";
            case "ASR_UNRECOGNIZED" -> "无法识别";
            case "DO_NOT_CALL" -> "拒绝联系";
            case "PENDING_REVIEW" -> "待人工确认";
            case "WORKFLOW_FAILED" -> "编排执行失败";
            default -> resultCode;
        };
    }
}
