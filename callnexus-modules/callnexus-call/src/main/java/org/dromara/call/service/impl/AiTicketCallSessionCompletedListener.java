package org.dromara.call.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.service.AiTicketDraftTriggerService;
import org.dromara.ai.service.model.AiTicketCallCompletedContext;
import org.dromara.call.domain.CallSessionCompletedEvent;
import org.dromara.call.service.CallSessionCompletedListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiTicketCallSessionCompletedListener implements CallSessionCompletedListener {
    private final AiTicketDraftTriggerService triggerService;

    @Override
    public void onCompleted(CallSessionCompletedEvent event) {
        triggerService.onCallCompleted(new AiTicketCallCompletedContext(
            event.tenantId(), event.sessionId(), event.businessCallId(), event.startedAt(), event.answeredAt(),
            event.endedAt(), event.durationSeconds(), event.billableSeconds(), event.hangupCause()));
    }
}
