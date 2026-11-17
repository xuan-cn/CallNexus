package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class DtmfCaptureNodeHandler implements AiWorkflowNodeHandler {
    public static final String TYPE = "DTMF_CAPTURE";
    public static final Set<String> BRANCHES = Set.of("SUCCESS", "TIMEOUT");

    @Override
    public Set<String> nodeTypes() {
        return Set.of(TYPE);
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        config(context.node().path("config"));
        return new AiWorkflowNodeResult(TYPE, null, "等待客户按键", null, Map.of());
    }

    public static Config config(JsonNode value) {
        JsonNode length = value.path("digitCount");
        JsonNode timeout = value.path("timeoutSeconds");
        String key = value.path("outputVariable").asText("");
        if (!length.isIntegralNumber() || !length.canConvertToInt() || length.asInt() < 1 || length.asInt() > 32
            || !timeout.isIntegralNumber() || !timeout.canConvertToInt() || timeout.asInt() < 3 || timeout.asInt() > 60
            || !key.matches("workflow\\.custom_[A-Za-z0-9_]+")) {
            throw new ServiceException("按键采集需要 1～32 位数字、3～60 秒等待时间和一个文本流程变量");
        }
        return new Config(length.asInt(), timeout.asInt(), key);
    }

    public record Config(int digitCount, int timeoutSeconds, String outputVariable) {
        public boolean accepts(String digits) {
            return digits != null && digits.length() == digitCount && digits.matches("[0-9]+");
        }
    }
}
