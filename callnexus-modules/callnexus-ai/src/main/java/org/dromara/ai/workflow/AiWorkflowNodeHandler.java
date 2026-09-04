package org.dromara.ai.workflow;

import java.util.Set;

public interface AiWorkflowNodeHandler {
    Set<String> nodeTypes();
    AiWorkflowNodeResult execute(AiWorkflowNodeContext context);
}
