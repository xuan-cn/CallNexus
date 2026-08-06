package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiConferenceActionResponse(
    Long conferenceId,
    String businessCallId,
    String action,
    boolean accepted
) {
    public static OpenApiConferenceActionResponse accepted(Long conferenceId, String businessCallId, String action) {
        return new OpenApiConferenceActionResponse(conferenceId, businessCallId, action, true);
    }
}
