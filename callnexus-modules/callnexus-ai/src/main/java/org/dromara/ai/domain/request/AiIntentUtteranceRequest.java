package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiIntentUtteranceRequest {
    @NotBlank
    @Size(max = 16)
    private String utteranceType;
    @NotBlank
    @Size(max = 1000)
    private String utteranceText;
}
