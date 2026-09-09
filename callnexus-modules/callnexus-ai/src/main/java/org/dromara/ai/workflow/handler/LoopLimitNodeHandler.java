package org.dromara.ai.workflow.handler;

import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class LoopLimitNodeHandler implements AiWorkflowNodeHandler {
    private static final int DEFAULT_MAX_ITERATIONS = 3;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("LOOP_LIMIT");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        String nodeId = context.node().path("id").asText();
        int maximum = context.node().path("config").path("maxIterations").asInt(DEFAULT_MAX_ITERATIONS);
        String counterKey = "workflow.loop." + nodeId + ".count";
        int count = number(context.variables().get(counterKey)) + 1;
        String branch = count <= maximum ? "CONTINUE" : "LIMIT";
        return new AiWorkflowNodeResult("CONTINUE", branch, null, null, Map.of(
            counterKey, count,
            "workflow.loopCount", count
        ));
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
