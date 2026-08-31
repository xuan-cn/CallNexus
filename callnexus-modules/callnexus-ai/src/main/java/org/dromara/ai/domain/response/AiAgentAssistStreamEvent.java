package org.dromara.ai.domain.response;

public record AiAgentAssistStreamEvent(
    String businessCallId,
    AiAgentAssistSuggestionResponse suggestion,
    AiCallTranscriptSegmentResponse segment,
    AiTicketDraftResponse ticketDraft
) {
}
