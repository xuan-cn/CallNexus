package org.dromara.ai.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiWorkflowNodeTraceResponse {
    private Long id;
    private String nodeId;
    private String nodeType;
    private String nodeName;
    private Integer turnNo;
    private String status;
    private String branchValue;
    private String inputSummary;
    private String outputSummary;
    private java.util.Map<String, Object> dialogueDetails;
    private java.util.Map<String, Object> ruleDetails;
    private Long durationMs;
    private LocalDateTime startedAt;
}
