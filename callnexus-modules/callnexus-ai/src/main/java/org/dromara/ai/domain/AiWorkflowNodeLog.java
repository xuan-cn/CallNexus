package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_workflow_node_log")
public class AiWorkflowNodeLog extends TenantEntity {
    @TableId private Long id;
    private String executionId;
    private String nodeId;
    private String nodeType;
    private String nodeName;
    private Integer turnNo;
    private Integer attemptNo;
    private String inputSummary;
    private String outputSummary;
    private String executionStatus;
    private String branchValue;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
}
