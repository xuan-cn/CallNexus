package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_workflow_wait")
public class AiWorkflowWait extends TenantEntity {
    @TableId private Long id;
    private String executionId;
    private String nodeId;
    private String waitType;
    private String waitToken;
    private String expectedInputType;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime resumedAt;
}
