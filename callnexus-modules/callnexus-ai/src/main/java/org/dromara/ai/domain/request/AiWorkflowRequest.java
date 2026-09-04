package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiWorkflowRequest {
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,64}$") private String workflowCode;
    @NotBlank @Size(max = 128) private String workflowName;
    @NotBlank @Pattern(regexp = "VOICE_INBOUND|VOICE_OUTBOUND|ONLINE_CHAT|COMMON") private String sceneType;
    @Size(max = 500) private String description;
    @NotNull private Boolean enabled;
}
