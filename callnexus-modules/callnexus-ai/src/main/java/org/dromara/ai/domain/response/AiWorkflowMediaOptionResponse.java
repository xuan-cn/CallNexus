package org.dromara.ai.domain.response;

import lombok.Data;

@Data
public class AiWorkflowMediaOptionResponse {
    private Long id;
    private String assetName;
    private String category;
    private String sourceType;
    private Long durationMs;
    private String publishStatus;
}
