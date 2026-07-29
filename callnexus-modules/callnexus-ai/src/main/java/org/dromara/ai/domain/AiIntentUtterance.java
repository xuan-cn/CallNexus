package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_intent_utterance")
public class AiIntentUtterance extends TenantEntity {
    @TableId private Long id;
    private Long intentId;
    private String utteranceType;
    private String utteranceText;
    private String normalizedText;
    private String textHash;
    private Integer sortOrder;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
