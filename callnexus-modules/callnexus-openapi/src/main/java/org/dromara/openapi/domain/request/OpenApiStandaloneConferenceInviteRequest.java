package org.dromara.openapi.domain.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiStandaloneConferenceInviteRequest(
    @NotNull(message = "agent_id is required") Long agentId,
    @NotEmpty(message = "target_extensions is required")
    @Size(max = 20, message = "target_extensions size must not exceed 20")
    List<@Pattern(regexp = "^[A-Za-z0-9._*#+-]{1,64}$", message = "target_extension format is invalid") String> targetExtensions
) {
}
