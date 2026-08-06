package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.dromara.call.domain.response.CallRecordingResponse;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiRecordingResponse(
    Long recordingId,
    Long callSessionId,
    String businessCallId,
    String status,
    String fileName,
    String contentType,
    Long fileSize,
    Long durationMs,
    String playbackUrl
) {
    public static OpenApiRecordingResponse from(CallRecordingResponse value) {
        return new OpenApiRecordingResponse(value.getMediaId(), value.getCallSessionId(), value.getBusinessCallId(),
            value.getStatus(), value.getFileName(), value.getContentType(), value.getFileSize(), value.getDurationMs(),
            value.getPlaybackUrl());
    }
}
