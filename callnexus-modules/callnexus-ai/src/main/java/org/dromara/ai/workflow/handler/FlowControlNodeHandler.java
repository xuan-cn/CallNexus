package org.dromara.ai.workflow.handler;

import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class FlowControlNodeHandler implements AiWorkflowNodeHandler {
    @Override
    public Set<String> nodeTypes() {
        return Set.of("START", "WAIT_INPUT", "END", "HANGUP");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        return switch (context.node().path("type").asText()) {
            case "START" -> AiWorkflowNodeResult.continueWith(null);
            case "WAIT_INPUT" -> AiWorkflowNodeResult.waitInput();
            case "HANGUP" -> new AiWorkflowNodeResult("HANGUP", null, null, null, java.util.Map.of());
            default -> AiWorkflowNodeResult.complete(null);
        };
    }
}
