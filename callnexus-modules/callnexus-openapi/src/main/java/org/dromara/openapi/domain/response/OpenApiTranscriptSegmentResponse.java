package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.dromara.ai.domain.response.AiCallTranscriptSegmentResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiTranscriptSegmentResponse(
    String speaker,
    String sourceType,
    Long agentId,
    Integer sentenceIndex,
    Integer startMs,
    Integer endMs,
    LocalDateTime messageTime,
    String text,
    BigDecimal confidence
) {
    public static OpenApiTranscriptSegmentResponse from(AiCallTranscriptSegmentResponse value) {
        return new OpenApiTranscriptSegmentResponse(value.getSpeaker(), value.getSourceType(), value.getAgentId(),
            value.getSentenceIndex(), value.getStartMs(), value.getEndMs(), value.getMessageTime(),
            value.getTextContent(), value.getConfidence());
    }
}
