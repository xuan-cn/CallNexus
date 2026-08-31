package org.dromara.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.event.AiTranscriptLifecycleEvent;
import org.dromara.ai.service.AiTicketDraftTriggerService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiTicketTranscriptLifecycleListener {
    private final AiTicketDraftTriggerService triggerService;

    @EventListener
    public void onTranscriptLifecycle(AiTranscriptLifecycleEvent event) {
        if (!"transcript.ready".equals(event.eventType())) return;
        Object value = event.payload() == null ? null : event.payload().get("transcript_id");
        Long transcriptId = value instanceof Number number ? number.longValue() : null;
        triggerService.onTranscriptReady(event.tenantId(), event.businessCallId(), transcriptId);
    }
}
