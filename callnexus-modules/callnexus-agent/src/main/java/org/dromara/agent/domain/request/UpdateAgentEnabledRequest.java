package org.dromara.agent.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateAgentEnabledRequest {
    @NotNull
    private Boolean enabled;

    @NotNull
    private Integer version;
}
