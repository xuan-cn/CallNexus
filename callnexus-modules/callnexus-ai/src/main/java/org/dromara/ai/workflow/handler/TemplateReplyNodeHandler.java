package org.dromara.ai.workflow.handler;

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
public class TemplateReplyNodeHandler implements AiWorkflowNodeHandler {
    private final AiWorkflowTemplateResolver resolver;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("TEMPLATE_REPLY");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        String output = resolver.resolve(context.node().path("config").path("text").asText(), context.variables());
        return new AiWorkflowNodeResult("SPEAK", null, output, "TTS", Map.of());
    }
}
