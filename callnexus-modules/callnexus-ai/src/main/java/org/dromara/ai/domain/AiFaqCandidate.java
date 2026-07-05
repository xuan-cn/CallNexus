package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_faq_candidate")
public class AiFaqCandidate extends TenantEntity {
    @TableId private Long id;
    private Long batchId;
    private Long knowledgeBaseId;
    @TableField("source_row_number")
    private Integer rowNumber;
    private String faqCode;
    private String faqName;
    private String standardQuestion;
    private String normalizedQuestion;
    private String standardAnswer;
    private String aliasesJson;
    private String answerMode;
    private String sourceLocation;
    private String sourceText;
    private BigDecimal confidence;
    private String status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private Long faqId;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
