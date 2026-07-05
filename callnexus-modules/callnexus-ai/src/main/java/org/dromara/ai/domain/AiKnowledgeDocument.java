package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_knowledge_document")
public class AiKnowledgeDocument extends TenantEntity {
    @TableId private Long id;
    private Long knowledgeBaseId;
    private String documentName;
    private String documentType;
    private Long currentVersionId;
    private String status;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
