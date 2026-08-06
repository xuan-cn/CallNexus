package org.dromara.openapi.domain.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiSupervisorActionRequest(
    @NotNull(message = "supervisor_agent_id is required") Long supervisorAgentId
) {
}
