package org.dromara.openapi.domain.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiConferenceMuteRequest(
    @NotNull(message = "agent_id is required") Long agentId,
    @NotNull(message = "muted is required") Boolean muted
) {
}
