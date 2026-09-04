package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AiAgentWorkflowBindingRequest {
    @NotBlank @Pattern(regexp = "VOICE_INBOUND|VOICE_OUTBOUND|ONLINE_CHAT") private String sceneType;
    @NotNull private Long workflowVersionId;
    @Pattern(regexp = "DEFAULT_CONVERSATION|TRANSFER_AGENT|END_CONVERSATION") private String fallbackAction;
    private Boolean enabled;
}
