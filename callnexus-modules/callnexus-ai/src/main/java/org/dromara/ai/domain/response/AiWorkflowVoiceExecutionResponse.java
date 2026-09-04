package org.dromara.ai.domain.response;

import lombok.Data;

@Data
public class AiWorkflowVoiceExecutionResponse {
    private String executionId;
    private Long workflowId;
    private Long workflowVersionId;
    private String status;
    private String actionType;
    private String text;
    private String target;
    private String waitingToken;
    private String failureMessage;
}
