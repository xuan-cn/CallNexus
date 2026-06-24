package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TtsTestRequest {
    @NotBlank
    private String text;
    private String voice;
}
