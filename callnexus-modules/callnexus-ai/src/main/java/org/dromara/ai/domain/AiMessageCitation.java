package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_message_citation")
public class AiMessageCitation extends TenantEntity {
    @TableId private Long id;
    private Long messageId;
    private String sourceType;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long documentVersionId;
    private Long chunkId;
    private Long faqId;
    private Long faqVersionId;
    private String sourceName;
    private String sourceLocation;
    private String quotedContent;
    private BigDecimal score;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
