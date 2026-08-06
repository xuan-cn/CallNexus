package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiAgentActiveCallResponse(
    Long agentId,
    boolean active,
    OpenApiCallDetailResponse call
) {
}
