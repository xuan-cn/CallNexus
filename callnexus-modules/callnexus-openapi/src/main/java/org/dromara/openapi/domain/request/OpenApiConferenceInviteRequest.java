package org.dromara.openapi.domain.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiConferenceInviteRequest(
    @NotNull(message = "agent_id is required") Long agentId,
    @NotBlank(message = "target_extension is required")
    @Pattern(regexp = "^[A-Za-z0-9._*#+-]{1,64}$", message = "target_extension format is invalid") String targetExtension
) {
}
