package org.dromara.openapi.domain.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiTransferCallRequest(
    @NotNull(message = "agent_id is required") Long agentId,
    @NotBlank(message = "target_extension is required")
    @Pattern(regexp = "^[0-9*#+]{2,32}$", message = "target_extension format is invalid") String targetExtension,
    String phoneMode
) {
}
