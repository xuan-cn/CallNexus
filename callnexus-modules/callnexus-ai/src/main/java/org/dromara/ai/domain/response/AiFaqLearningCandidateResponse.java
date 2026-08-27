package org.dromara.ai.domain.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AiFaqLearningCandidateResponse {
    private Long id;
    private Long knowledgeBaseId;
    private String knowledgeBaseName;
    private Long agentId;
    private String agentName;
    private Long conversationId;
    private String sourceChannel;
    private String standardQuestion;
    private String standardAnswer;
    private String faqCode;
    private String faqName;
    private List<String> aliases;
    private String answerMode;
    private BigDecimal bestFaqScore;
    private BigDecimal bestDocumentScore;
    private Integer occurrenceCount;
    private LocalDateTime firstOccurredAt;
    private LocalDateTime lastOccurredAt;
    private String status;
    private Long targetFaqId;
    private String reviewReason;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private Integer version;
}
