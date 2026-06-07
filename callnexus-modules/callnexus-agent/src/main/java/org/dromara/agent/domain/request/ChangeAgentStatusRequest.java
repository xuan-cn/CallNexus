package org.dromara.agent.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.dromara.agent.domain.AgentPresenceStatus;

@Data
public class ChangeAgentStatusRequest {
    @NotNull
    private AgentPresenceStatus status;
}
