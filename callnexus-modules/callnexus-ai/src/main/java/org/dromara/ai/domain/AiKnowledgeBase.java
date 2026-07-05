package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_knowledge_base")
public class AiKnowledgeBase extends TenantEntity {
    @TableId private Long id;
    private String knowledgeCode;
    private String knowledgeName;
    private String description;
    private Long embeddingModelId;
    private String collectionName;
    private Long pendingEmbeddingModelId;
    private String pendingCollectionName;
    private String rebuildBatchId;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer defaultTopK;
    private BigDecimal scoreThreshold;
    private String status;
    private Integer documentCount;
    private Integer faqCount;
    private Integer chunkCount;
    private LocalDateTime lastIndexedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String failureReason;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
