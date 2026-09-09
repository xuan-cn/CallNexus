package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.workflow.handler.LoopLimitNodeHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class LoopLimitNodeHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void routesToLimitAfterConfiguredIterations() throws Exception {
        LoopLimitNodeHandler handler = new LoopLimitNodeHandler();
        Map<String, Object> variables = new LinkedHashMap<>();
        AiWorkflowNodeContext context = new AiWorkflowNodeContext(
            OBJECT_MAPPER.readTree("""
                {"id":"productLoop","type":"LOOP_LIMIT","config":{"maxIterations":2}}
                """),
            variables,
            null,
            1L
        );

        AiWorkflowNodeResult first = handler.execute(context);
        variables.putAll(first.variableUpdates());
        AiWorkflowNodeResult second = handler.execute(context);
        variables.putAll(second.variableUpdates());
        AiWorkflowNodeResult third = handler.execute(context);

        assertThat(first.branchValue()).isEqualTo("CONTINUE");
        assertThat(second.branchValue()).isEqualTo("CONTINUE");
        assertThat(third.branchValue()).isEqualTo("LIMIT");
        assertThat(third.variableUpdates()).containsEntry("workflow.loopCount", 3);
        assertThat(third.variableUpdates()).containsEntry("workflow.loop.productLoop.count", 3);
    }
}
