package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_knowledge_faq_version")
public class AiKnowledgeFaqVersion extends TenantEntity {
    @TableId private Long id;
    private Long knowledgeBaseId;
    private Long faqId;
    private Integer versionNo;
    private String standardQuestion;
    private String normalizedQuestion;
    private String standardAnswer;
    private String questionHash;
    private String answerHash;
    private String primaryQdrantPointId;
    private String indexStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
