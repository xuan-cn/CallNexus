package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiCallActionResponse(
    String businessCallId,
    String action,
    boolean accepted
) {
    public static OpenApiCallActionResponse accepted(String businessCallId, String action) {
        return new OpenApiCallActionResponse(businessCallId, action, true);
    }
}
