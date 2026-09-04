package org.dromara.ai.workflow.handler;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.ai.workflow.AiWorkflowOutboundWritebackService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class OutboundWritebackNodeHandler implements AiWorkflowNodeHandler {
    private final ObjectProvider<AiWorkflowOutboundWritebackService> writebackServiceProvider;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("AUTO_OUTBOUND_WRITEBACK");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        String resultCode = context.node().path("config").path("resultCode").asText();
        Map<String, Object> updates = Map.of("workflow.outboundResult", resultCode);
        if (Boolean.TRUE.equals(context.variables().get("workflow.testMode"))) {
            return new AiWorkflowNodeResult("CONTINUE", null, "模拟记录外呼结果：" + resultCode, null, updates);
        }
        if (!"VOICE_OUTBOUND".equals(context.variables().get("workflow.sceneType"))) {
            throw new ServiceException("外呼结果回写节点只能在语音外呼场景执行");
        }
        Long taskId = requiredLong(context.variables(), "outbound.taskId");
        Long memberId = requiredLong(context.variables(), "outbound.memberId");
        String businessCallId = String.valueOf(context.variables().getOrDefault("call.businessCallId", ""));
        if (businessCallId.isBlank()) {
            throw new ServiceException("外呼结果回写缺少通话 ID");
        }
        AiWorkflowOutboundWritebackService service = writebackServiceProvider.getIfAvailable();
        if (service == null) {
            throw new ServiceException("外呼结果回写服务未启用");
        }
        service.writeBack(taskId, memberId, businessCallId, resultCode);
        return new AiWorkflowNodeResult("CONTINUE", null, "已记录外呼结果：" + resultCode, null, updates);
    }

    private Long requiredLong(Map<String, Object> variables, String key) {
        Object value = variables.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            if (value == null || String.valueOf(value).isBlank()) {
                throw new ServiceException("外呼结果回写缺少参数：" + key);
            }
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            throw new ServiceException("外呼结果回写参数无效：" + key);
        }
    }
}
