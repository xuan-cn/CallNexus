package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_knowledge_task")
public class AiKnowledgeTask extends TenantEntity {
    @TableId private Long id;
    private String taskType;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long documentVersionId;
    private Long faqId;
    private Long faqVersionId;
    private Long candidateBatchId;
    private Long targetEmbeddingModelId;
    private String targetCollectionName;
    private String rebuildBatchId;
    private String status;
    private Integer retryCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRetryAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaseOwner;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseExpiresAt;
    private Integer progressTotal;
    private Integer progressCompleted;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
