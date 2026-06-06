package org.dromara.agent.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAgentRequest {
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$")
    private String agentCode;
    @NotBlank
    @Size(max = 64)
    private String agentName;
    private Long userId;
}
