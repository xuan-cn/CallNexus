package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiAgentAssistSegmentRequest;
import org.dromara.ai.domain.request.AiTicketDraftUpdateRequest;
import org.dromara.ai.domain.response.AiAgentAssistDetailResponse;
import org.dromara.ai.domain.response.AiTicketDraftResponse;

public interface AiAgentAssistService {
    void accept(AiAgentAssistSegmentRequest request);

    AiAgentAssistDetailResponse detail(String businessCallId);

    void regenerate(String businessCallId, Long suggestionId);

    Long approveTicketDraft(String businessCallId, Long draftId, Integer version);

    AiTicketDraftResponse updateTicketDraft(String businessCallId, Long draftId,
                                            AiTicketDraftUpdateRequest request);
}
