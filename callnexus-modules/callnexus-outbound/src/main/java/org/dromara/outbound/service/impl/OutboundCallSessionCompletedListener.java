package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.CallSessionCompletedEvent;
import org.dromara.call.service.CallSessionCompletedListener;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.outbound.domain.OutboundAttempt;
import org.dromara.outbound.mapper.OutboundAttemptMapper;
import org.dromara.outbound.service.OutboundResultSuggestionService;
import org.dromara.outbound.service.OutboundAutomaticRetryService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboundCallSessionCompletedListener implements CallSessionCompletedListener {
    private final OutboundAttemptMapper attemptMapper;
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
            .set(OutboundAttempt::getHangupCause, event.hangupCause()));
        if (updated > 0) {
            automaticRetryService.applySystemSuggestion(event.outboundMemberId(), event.businessCallId(), suggestedResultCode);
            log.info("外呼尝试通话数据回写完成，businessCallId={}，memberId={}，billableSeconds={}，hangupCause={}",
                event.businessCallId(), event.outboundMemberId(), event.destinationBillableSeconds(), event.hangupCause());
        }
    }
}
