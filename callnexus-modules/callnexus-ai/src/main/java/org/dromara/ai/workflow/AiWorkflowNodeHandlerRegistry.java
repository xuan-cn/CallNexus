package org.dromara.ai.workflow;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiWorkflowNodeHandlerRegistry {
    private final Map<String, AiWorkflowNodeHandler> handlers = new HashMap<>();

    public AiWorkflowNodeHandlerRegistry(List<AiWorkflowNodeHandler> values) {
        values.forEach(handler -> handler.nodeTypes().forEach(type -> {
            if (handlers.putIfAbsent(type, handler) != null) throw new IllegalStateException("重复的 AI 工作流节点处理器：" + type);
        }));
    }

    public AiWorkflowNodeHandler require(String nodeType) {
        AiWorkflowNodeHandler handler = handlers.get(nodeType);
        if (handler == null) throw new ServiceException("当前运行时暂不支持节点：" + nodeType);
        return handler;
    }
}
