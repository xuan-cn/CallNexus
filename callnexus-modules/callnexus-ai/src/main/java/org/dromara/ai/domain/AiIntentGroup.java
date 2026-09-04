package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_intent_group")
public class AiIntentGroup extends TenantEntity {
    @TableId private Long id;
    private String groupCode;
    private String groupName;
    private String description;
    private Integer sortOrder;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
