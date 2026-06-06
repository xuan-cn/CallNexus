package org.dromara.agent.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BindAgentExtensionRequest {
    @NotNull
    private Long sipAccountId;
}
