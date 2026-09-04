package org.dromara.ai.workflow.handler;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
@RequiredArgsConstructor
public class IntentRouteNodeHandler implements AiWorkflowNodeHandler {
    private final AiIntentApplicationService intentService;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("INTENT_ROUTE");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        if (context.aiAgentId() == null) throw new ServiceException("测试意图判断前请选择 AI 助手");
        AiIntentRecognitionRequest request = new AiIntentRecognitionRequest();
        request.setAgentId(context.aiAgentId());
        request.setText(context.currentInput());
        Set<String> configured = StreamSupport.stream(
                context.node().path("config").path("intentCodes").spliterator(), false)
            .map(item -> item.asText("")).filter(item -> !item.isBlank()).collect(Collectors.toSet());
        request.setIntentCodes(configured.stream().toList());
        AiIntentRecognitionResponse recognition = intentService.recognize(request);
        String branch = recognition.isMatched() && configured.contains(recognition.getIntentCode())
            ? recognition.getIntentCode() : "FALLBACK";
        String output = recognition.isMatched()
            ? "识别意图：" + recognition.getIntentName() + "，置信度：" + recognition.getConfidence()
            : "未命中已配置意图";
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("conversation.intentCode", branch);
        updates.put("workflow.clarifyCount", "FALLBACK".equals(branch)
            ? clarifyCount(context.variables().get("workflow.clarifyCount")) + 1 : 0);
        return new AiWorkflowNodeResult("CONTINUE", branch, output, null,
            updates);
    }

    private int clarifyCount(Object value) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        if (value == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
