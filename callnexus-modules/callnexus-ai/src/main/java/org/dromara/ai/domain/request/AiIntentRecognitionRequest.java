package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AiIntentRecognitionRequest {
    @NotNull private Long agentId;
    @NotBlank @Size(max = 2000) private String text;
    private List<String> intentCodes;
    private List<Long> groupIds;
}
