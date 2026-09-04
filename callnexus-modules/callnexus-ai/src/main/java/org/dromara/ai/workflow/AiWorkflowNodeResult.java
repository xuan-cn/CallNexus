package org.dromara.ai.workflow;

import java.util.Map;

public record AiWorkflowNodeResult(
    String status,
    String branchValue,
    String output,
    String waitType,
    Map<String, Object> variableUpdates
) {
    public static AiWorkflowNodeResult continueWith(String branchValue) {
        return new AiWorkflowNodeResult("CONTINUE", branchValue, null, null, Map.of());
    }

    public static AiWorkflowNodeResult waitInput() {
        return new AiWorkflowNodeResult("WAIT_INPUT", null, null, "INPUT", Map.of());
    }

    public static AiWorkflowNodeResult complete(String output) {
        return new AiWorkflowNodeResult("COMPLETED", null, output, null, Map.of());
    }
}
