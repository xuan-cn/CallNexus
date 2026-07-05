package org.dromara.ai.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class AiKnowledgeBaseRequest {
    @NotBlank private String knowledgeCode;
    @NotBlank private String knowledgeName;
    private String description;
    @NotNull private Long embeddingModelId;
    @Min(100) @Max(5000) private Integer chunkSize;
    @Min(0) @Max(1000) private Integer chunkOverlap;
    @Min(1) @Max(50) private Integer defaultTopK;
    @DecimalMin("0") @DecimalMax("1") private BigDecimal scoreThreshold;
    private Boolean enabled;
    private Integer version;
}
