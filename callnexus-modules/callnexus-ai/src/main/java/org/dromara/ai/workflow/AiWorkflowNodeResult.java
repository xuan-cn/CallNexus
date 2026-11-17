package org.dromara.ai.workflow;

import org.dromara.ai.domain.response.AiWorkflowTicketPreview;

import java.util.Map;

public record AiWorkflowNodeResult(
    String status,
    String branchValue,
    String output,
    String waitType,
    Map<String, Object> variableUpdates,
    AiWorkflowTicketPreview ticketPreview
) {
    public AiWorkflowNodeResult(String status, String branchValue, String output, String waitType, Map<String, Object> variableUpdates) {
        this(status, branchValue, output, waitType, variableUpdates, null);
    }

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
