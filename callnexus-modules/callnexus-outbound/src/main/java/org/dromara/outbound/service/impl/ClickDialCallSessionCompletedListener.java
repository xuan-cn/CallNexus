package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.CallSessionCompletedEvent;
import org.dromara.call.service.CallSessionCompletedListener;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.customer.customer.domain.CustomerAssignment;
import org.dromara.customer.customer.mapper.CustomerAssignmentMapper;
import org.dromara.outbound.domain.CustomerDialAttempt;
import org.dromara.outbound.mapper.CustomerDialAttemptMapper;
import org.dromara.outbound.service.OutboundResultSuggestionService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClickDialCallSessionCompletedListener implements CallSessionCompletedListener {
    private final CustomerDialAttemptMapper attemptMapper;
    private final CustomerAssignmentMapper assignmentMapper;
    private final OutboundResultSuggestionService resultSuggestionService;

    @Override
    public void onCompleted(CallSessionCompletedEvent event) {
        if (event.tenantId() == null || event.tenantId().isBlank() || event.businessCallId() == null) return;
        TenantHelper.dynamic(event.tenantId(), () -> updateAttempt(event));
    }

    private void updateAttempt(CallSessionCompletedEvent event) {
        CustomerDialAttempt attempt = attemptMapper.selectOne(new LambdaQueryWrapper<CustomerDialAttempt>()
            .eq(CustomerDialAttempt::getBusinessCallId, event.businessCallId())
            .last("LIMIT 1"));
        if (attempt == null) return;
        String suggestedResult = resultSuggestionService.suggest(
            event.hangupCause(), event.destinationAnsweredAt() != null);
        int updated = attemptMapper.update(null, new LambdaUpdateWrapper<CustomerDialAttempt>()
            .eq(CustomerDialAttempt::getId, attempt.getId())
            .set(CustomerDialAttempt::getStatus, "ENDED")
            .set(CustomerDialAttempt::getStartedAt, event.startedAt())
            .set(CustomerDialAttempt::getAnsweredAt, event.destinationAnsweredAt())
            .set(CustomerDialAttempt::getEndedAt, event.endedAt())
            .set(CustomerDialAttempt::getDurationSeconds, event.durationSeconds())
            .set(CustomerDialAttempt::getBillableSeconds, event.destinationBillableSeconds())
            .set(CustomerDialAttempt::getSuggestedResultCode, suggestedResult)
            .set(CustomerDialAttempt::getHangupCause, event.hangupCause()));
        if (updated == 0) return;
        assignmentMapper.update(null, new LambdaUpdateWrapper<CustomerAssignment>()
            .eq(CustomerAssignment::getId, attempt.getAssignmentId())
            .eq(CustomerAssignment::getAgentId, attempt.getAgentId())
            .eq(CustomerAssignment::getEnabled, true)
            .eq(CustomerAssignment::getDialStatus, "PROCESSING")
            .set(CustomerAssignment::getDialStatus, "PROCESSING")
            .set(CustomerAssignment::getLastResultCode, suggestedResult));
        log.info("点击拨号通话数据回写完成，businessCallId={}，assignmentId={}，suggestedResult={}",
            event.businessCallId(), attempt.getAssignmentId(), suggestedResult);
    }
}
