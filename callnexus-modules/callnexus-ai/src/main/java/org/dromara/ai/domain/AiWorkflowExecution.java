package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_workflow_execution")
public class AiWorkflowExecution extends TenantEntity {
    @TableId private Long id;
    private String executionId;
    private Long workflowId;
    private Long workflowVersionId;
    private Long aiAgentId;
    private String businessCallId;
    private Long conversationId;
    private String channelType;
    private String status;
    private String currentNodeId;
    private String contextJson;
    private Integer turnNo;
    private Integer totalNodeCount;
    private String lastInputId;
    private LocalDateTime startedAt;
    private LocalDateTime lastActiveAt;
    private LocalDateTime endedAt;
    private String failureCode;
    private String failureMessage;
    @Version private Integer lockVersion;
}
