package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.dromara.ai.domain.response.AiCallTranscriptResponse;

import java.time.LocalDateTime;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiTranscriptResponse(
    Long transcriptId,
    Long callSessionId,
    String businessCallId,
    String status,
    String fullText,
    String failureReason,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    List<OpenApiTranscriptSegmentResponse> segments
) {
    public static OpenApiTranscriptResponse from(AiCallTranscriptResponse value) {
        List<OpenApiTranscriptSegmentResponse> segments = value.getSegments() == null ? List.of()
            : value.getSegments().stream().map(OpenApiTranscriptSegmentResponse::from).toList();
        return new OpenApiTranscriptResponse(value.getId(), value.getCallSessionId(), value.getBusinessCallId(),
            value.getStatus(), value.getFullText(), value.getFailureReason(), value.getStartedAt(),
            value.getFinishedAt(), segments);
    }
}
