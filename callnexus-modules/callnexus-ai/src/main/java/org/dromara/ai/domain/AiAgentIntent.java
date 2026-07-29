package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_agent_intent")
public class AiAgentIntent extends TenantEntity {
    @TableId private Long id;
    private Long agentId;
    private Long intentId;
    private Integer priority;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
