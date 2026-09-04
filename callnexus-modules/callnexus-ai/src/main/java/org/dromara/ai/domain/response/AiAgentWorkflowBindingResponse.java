package org.dromara.ai.domain.response;

import lombok.Data;

@Data
public class AiAgentWorkflowBindingResponse {
    private Long id;
    private Long aiAgentId;
    private String sceneType;
    private Long workflowId;
    private String workflowName;
    private Long workflowVersionId;
    private Integer workflowVersionNo;
    private String fallbackAction;
    private Boolean enabled;
}
