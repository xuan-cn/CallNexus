package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.CallSessionCompletedEvent;
import org.dromara.call.service.CallSessionCompletedListener;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.outbound.domain.OutboundAttempt;
import org.dromara.outbound.domain.AutoOutboundDispatch;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.mapper.AutoOutboundDispatchMapper;
import org.dromara.outbound.mapper.OutboundAttemptMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.service.OutboundResultSuggestionService;
import org.dromara.outbound.service.OutboundAutomaticRetryService;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboundCallSessionCompletedListener implements CallSessionCompletedListener {
    private final OutboundAttemptMapper attemptMapper;
    private final AutoOutboundDispatchMapper dispatchMapper;
    private final OutboundTaskMapper taskMapper;
    private final CustomerApplicationService customerService;
    private final OutboundResultSuggestionService resultSuggestionService;
    private final OutboundAutomaticRetryService automaticRetryService;

    @Override
    public void onCompleted(CallSessionCompletedEvent event) {
        if (event.tenantId() == null || event.tenantId().isBlank()
            || event.outboundMemberId() == null || event.businessCallId() == null) return;
        TenantHelper.dynamic(event.tenantId(), () -> updateAttempt(event));
    }

    private void updateAttempt(CallSessionCompletedEvent event) {
        String suggestedResultCode = resultSuggestionService.suggest(event.hangupCause(), event.destinationAnsweredAt() != null);
        OutboundResultSuggestionService.FailureClassification failure =
            resultSuggestionService.classify(event.hangupCause(), event.destinationAnsweredAt() != null);
        int updated = attemptMapper.update(null, new LambdaUpdateWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getBusinessCallId, event.businessCallId())
            .eq(OutboundAttempt::getMemberId, event.outboundMemberId())
            .set(OutboundAttempt::getStatus, "ENDED")
            .set(OutboundAttempt::getStartedAt, event.startedAt())
            .set(OutboundAttempt::getAnsweredAt, event.destinationAnsweredAt())
            .set(OutboundAttempt::getEndedAt, event.endedAt())
            .set(OutboundAttempt::getDurationSeconds, event.durationSeconds())
            .set(OutboundAttempt::getBillableSeconds, event.destinationBillableSeconds())
            .set(OutboundAttempt::getSuggestedResultCode, suggestedResultCode)
            .set(OutboundAttempt::getHangupCause, event.hangupCause())
            .set(OutboundAttempt::getFailureCategory, failure.category())
            .set(OutboundAttempt::getRetryable, failure.retryable()));
        if (updated > 0) {
            dispatchMapper.update(null, new LambdaUpdateWrapper<AutoOutboundDispatch>()
                .eq(AutoOutboundDispatch::getBusinessCallId, event.businessCallId())
                .eq(AutoOutboundDispatch::getMemberId, event.outboundMemberId())
                .in(AutoOutboundDispatch::getStatus, "READY", "PROCESSING")
                .set(AutoOutboundDispatch::getStatus, "COMPLETED")
                .set(AutoOutboundDispatch::getAnsweredAt, event.destinationAnsweredAt())
                .set(AutoOutboundDispatch::getCompletedAt, event.endedAt())
                .set(AutoOutboundDispatch::getHangupCause, event.hangupCause())
                .set(AutoOutboundDispatch::getLeaseOwner, null)
                .set(AutoOutboundDispatch::getLeaseExpiresAt, null));
            writeBackCustomerResult(event, suggestedResultCode, failure);
            automaticRetryService.applySystemSuggestion(event.outboundMemberId(), event.businessCallId(), suggestedResultCode);
            log.info("外呼尝试通话数据回写完成，businessCallId={}，memberId={}，billableSeconds={}，hangupCause={}",
                event.businessCallId(), event.outboundMemberId(), event.destinationBillableSeconds(), event.hangupCause());
        }
    }

    private void writeBackCustomerResult(
        CallSessionCompletedEvent event,
        String resultCode,
        OutboundResultSuggestionService.FailureClassification failure
    ) {
        OutboundAttempt attempt = attemptMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getBusinessCallId, event.businessCallId())
            .eq(OutboundAttempt::getMemberId, event.outboundMemberId())
            .last("LIMIT 1"));
        if (attempt == null || attempt.getCustomerId() == null) return;
        OutboundTask task = taskMapper.selectById(attempt.getTaskId());
        if (task == null || Boolean.FALSE.equals(task.getResultWritebackEnabled())) return;
        boolean connected = event.destinationAnsweredAt() != null;
        String resultLabel = resultSuggestionService.resultLabel(resultCode);
        String causeLabel = resultSuggestionService.hangupCauseLabel(event.hangupCause());
        String categoryLabel = resultSuggestionService.failureCategoryLabel(failure.category());
        String content = "自动外呼任务“" + task.getTaskName() + "”第" + attempt.getAttemptNo() + "次呼叫："
            + resultLabel
            + (causeLabel == null ? "" : "，挂机原因：" + causeLabel)
            + (categoryLabel == null ? "" : "，失败分类：" + categoryLabel)
            + "，通话ID：" + event.businessCallId();
        customerService.recordOutboundResult(attempt.getCustomerId(), attempt.getId(), content,
            connected ? task.getConnectedTag() : task.getFailedTag());
    }
}
