package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_intent")
public class AiIntent extends TenantEntity {
    @TableId private Long id;
    private Long groupId;
    private String intentCode;
    private String intentName;
    private String intentType;
    private String description;
    private String actionType;
    private String actionConfigJson;
    private String responseTemplate;
    private BigDecimal confidenceThreshold;
    private Integer priority;
    private Boolean confirmationRequired;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
