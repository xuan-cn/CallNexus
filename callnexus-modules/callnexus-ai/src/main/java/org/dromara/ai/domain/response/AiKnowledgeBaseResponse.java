package org.dromara.ai.domain.response;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AiKnowledgeBaseResponse {
    private Long id;
    private String knowledgeCode;
    private String knowledgeName;
    private String description;
    private Long embeddingModelId;
    private String embeddingModelName;
    private String collectionName;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer defaultTopK;
    private BigDecimal scoreThreshold;
    private String status;
    private Integer documentCount;
    private Integer faqCount;
    private Integer chunkCount;
    private LocalDateTime lastIndexedAt;
    private String failureReason;
    private Boolean enabled;
    private Integer version;
}
