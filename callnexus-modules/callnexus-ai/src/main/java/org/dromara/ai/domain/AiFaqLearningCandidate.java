package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_faq_learning_candidate")
public class AiFaqLearningCandidate extends TenantEntity {
    @TableId private Long id;
    private Long knowledgeBaseId;
    private Long agentId;
    private Long conversationId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String sourceChannel;
    private String standardQuestion;
    private String normalizedQuestion;
    private String questionHash;
    private String standardAnswer;
    private String answerHash;
    private String faqCode;
    private String faqName;
    private String aliasesJson;
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
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
