package org.dromara.ai.service;

import org.dromara.ai.service.model.AiTicketCallCompletedContext;

public interface AiTicketDraftTriggerService {
    void onCallCompleted(AiTicketCallCompletedContext context);
    void onTranscriptReady(String tenantId, String businessCallId, Long transcriptId);
    void onTranscriptSegment(String tenantId, String businessCallId, Long transcriptId);
    void onTransferToAgent(String tenantId, String businessCallId, Long transcriptId);
}
