package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiSupervisionResponse(
    String businessCallId,
    String action,
    boolean accepted,
    Long supervisorAgentId,
    String targetExtension
) {
    public static OpenApiSupervisionResponse accepted(String businessCallId, String action,
                                                       Long supervisorAgentId, String targetExtension) {
        return new OpenApiSupervisionResponse(businessCallId, action, true, supervisorAgentId, targetExtension);
    }
}
