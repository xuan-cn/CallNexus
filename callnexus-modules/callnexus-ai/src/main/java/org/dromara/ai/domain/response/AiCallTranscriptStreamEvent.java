package org.dromara.ai.domain.response;

public record AiCallTranscriptStreamEvent(
    Long callSessionId,
    Long transcriptId,
    AiCallTranscriptSegmentResponse segment
) {
}
