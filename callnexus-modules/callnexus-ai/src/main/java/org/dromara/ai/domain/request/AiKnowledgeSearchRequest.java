package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiKnowledgeSearchRequest {
    @NotBlank private String query;
    private Integer limit;
    private String sourceType;
}
