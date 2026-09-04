package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiWorkflowDraftRequest {
    @Size(max = 128) private String versionName;
    @NotBlank private String definitionJson;
}
