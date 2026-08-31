package org.dromara.ai.domain.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentAssistDetailResponse {
    private Long sessionId;
    private Long callSessionId;
    private String businessCallId;
    private Long skillGroupId;
    private Long assistAgentId;
    private String assistAgentName;
    private String sessionState;
    private AiTicketDraftResponse ticketDraft;
    private List<AiCallTranscriptSegmentResponse> transcriptSegments = new ArrayList<>();
    private List<AiAgentAssistSuggestionResponse> suggestions = new ArrayList<>();
}
