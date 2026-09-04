package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiWorkflowTestInputRequest {
    @NotBlank @Size(max = 128) private String inputId;
    @NotBlank @Size(max = 2000) private String text;
}
