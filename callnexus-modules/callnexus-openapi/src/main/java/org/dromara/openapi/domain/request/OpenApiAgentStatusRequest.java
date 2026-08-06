package org.dromara.openapi.domain.request;

import jakarta.validation.constraints.NotNull;
import org.dromara.agent.domain.AgentPresenceStatus;

public record OpenApiAgentStatusRequest(
    @NotNull(message = "status is required") AgentPresenceStatus status
) {
}
