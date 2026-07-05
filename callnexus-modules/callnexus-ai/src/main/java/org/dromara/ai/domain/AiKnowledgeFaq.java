package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_knowledge_faq")
public class AiKnowledgeFaq extends TenantEntity {
    @TableId private Long id;
    private Long knowledgeBaseId;
    private String faqCode;
    private String faqName;
    private Long currentVersionId;
    private String status;
    private String answerMode;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
