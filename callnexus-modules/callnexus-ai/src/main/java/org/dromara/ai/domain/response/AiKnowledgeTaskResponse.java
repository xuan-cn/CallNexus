package org.dromara.ai.domain.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiKnowledgeTaskResponse {
    private Long id;
    private String taskType;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long faqId;
    private String status;
    private Integer retryCount;
    private Integer progressTotal;
    private Integer progressCompleted;
    private String failureReason;
    private LocalDateTime nextRetryAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
