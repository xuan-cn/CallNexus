package org.dromara.ai.domain.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class AiWorkflowTestExecutionResponse {
    private String executionId;
    private Long workflowId;
    private Long workflowVersionId;
    private Integer workflowVersionNo;
    private String status;
    private String currentNodeId;
    private Integer turnNo;
    private String waitingType;
    private String waitingToken;
    private List<String> outputMessages = new ArrayList<>();
    private Map<String, Object> variables;
    private List<AiWorkflowNodeTraceResponse> traces = new ArrayList<>();
    private String failureMessage;
}
