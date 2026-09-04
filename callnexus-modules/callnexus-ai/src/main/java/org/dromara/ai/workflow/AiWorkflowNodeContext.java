package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record AiWorkflowNodeContext(
    JsonNode node,
    Map<String, Object> variables,
    String currentInput,
    Long aiAgentId
) {
}
