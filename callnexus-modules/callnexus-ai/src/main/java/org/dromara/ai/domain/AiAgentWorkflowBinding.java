package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_agent_workflow_binding")
public class AiAgentWorkflowBinding extends TenantEntity {
    @TableId private Long id;
    private Long aiAgentId;
    private String sceneType;
    private Long workflowId;
    private Long workflowVersionId;
    private String fallbackAction;
    private Boolean enabled;
}
