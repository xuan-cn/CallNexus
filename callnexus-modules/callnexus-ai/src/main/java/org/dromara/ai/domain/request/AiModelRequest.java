package org.dromara.ai.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class AiModelRequest {
    @NotNull private Long providerId;
    @NotBlank private String modelCode;
    @NotBlank private String modelName;
    @NotBlank private String capability;
    @Min(1) private Integer maxBatchSize;
    @Min(1) private Integer maxInputTokens;
    private Boolean defaultModel;
    private String requestOptionsJson;
    private Boolean enabled;
    private Integer version;
}
