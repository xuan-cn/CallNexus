package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_knowledge_faq_alias")
public class AiKnowledgeFaqAlias extends TenantEntity {
    @TableId private Long id;
    private Long knowledgeBaseId;
    private Long faqId;
    private Long faqVersionId;
    private String aliasQuestion;
    private String normalizedQuestion;
    private String questionHash;
    private String qdrantPointId;
    private String indexState;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
