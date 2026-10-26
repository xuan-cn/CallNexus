package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record AiWorkflowNodeContext(
    JsonNode node,
    Map<String, Object> variables,
    String currentInput,
    Long aiAgentId,
    String channelType,
    Map<String, Object> workflowSettings
) {
    public AiWorkflowNodeContext(JsonNode node, Map<String, Object> variables, String currentInput, Long aiAgentId) {
        this(node, variables, currentInput, aiAgentId, null, Map.of());
    }

    public AiWorkflowNodeContext(JsonNode node, Map<String, Object> variables, String currentInput,
                                 Long aiAgentId, String channelType) {
        this(node, variables, currentInput, aiAgentId, channelType, Map.of());
    }
}
