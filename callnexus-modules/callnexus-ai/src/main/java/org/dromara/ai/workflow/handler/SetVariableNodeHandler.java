package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.ai.workflow.AiWorkflowTemplateResolver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SetVariableNodeHandler implements AiWorkflowNodeHandler {
    private final AiWorkflowTemplateResolver resolver;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("SET_VARIABLE");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        String key = context.node().path("config").path("key").asText();
        JsonNode raw = context.node().path("config").get("value");
        Object value = raw == null || raw.isNull() ? null : raw.isBoolean() ? raw.asBoolean()
            : raw.isNumber() ? raw.numberValue() : resolver.resolve(raw.asText(), context.variables());
        return new AiWorkflowNodeResult("CONTINUE", null, "已记录流程信息", null, Map.of(key, value == null ? "" : value));
    }
}
