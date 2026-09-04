package org.dromara.ai.workflow.handler;

import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class SimulatedBusinessNodeHandler implements AiWorkflowNodeHandler {
    private static final Set<String> ASYNC_TYPES = Set.of("TICKET_DRAFT_CREATE");

    @Override
    public Set<String> nodeTypes() {
        return Set.of("TICKET_DRAFT_CREATE",
            "TRANSFER_QUEUE", "TRANSFER_EXTENSION", "TRANSFER_IVR");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        String type = context.node().path("type").asText();
        if (ASYNC_TYPES.contains(type)) {
            return new AiWorkflowNodeResult("WAIT_ASYNC", null, "阶段二测试台暂不执行该异步业务节点", "ASYNC_CALLBACK", Map.of());
        }
        if (type.startsWith("TRANSFER_")) {
            String target = switch (type) {
                case "TRANSFER_QUEUE" -> context.node().path("config").path("queueCode").asText();
                case "TRANSFER_EXTENSION" -> context.node().path("config").path("extension").asText();
                case "TRANSFER_IVR" -> context.node().path("config").path("ivrFlowId").asText();
                default -> "";
            };
            return new AiWorkflowNodeResult("TRANSFERRED", null, target, null, Map.of());
        }
        return new AiWorkflowNodeResult("CONTINUE", null, null, null, Map.of());
    }
}
