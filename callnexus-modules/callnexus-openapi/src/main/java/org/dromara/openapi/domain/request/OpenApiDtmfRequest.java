package org.dromara.openapi.domain.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiDtmfRequest(
    @NotNull(message = "agent_id is required") Long agentId,
    @NotBlank(message = "digits is required")
    @Pattern(regexp = "^[0-9A-Da-d*#]{1,32}$", message = "digits format is invalid") String digits
) {
}
