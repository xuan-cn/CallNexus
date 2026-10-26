package org.dromara.ai.workflow.handler;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.ai.workflow.AiWorkflowTemplateResolver;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
@RequiredArgsConstructor
public class IntentRouteNodeHandler implements AiWorkflowNodeHandler {
    private static final Set<String> RETRY_MODES = Set.of("FIXED_RETRY", "AI_RETRY");
    private static final String DEFAULT_AI_RETRY_PROMPT = """
        请先简短回答客户刚才的问题，再自然地重新询问上一轮问题。不要说明意图识别失败。客户原话：{{conversation.currentInput}}
        """;
    private static final String DEFAULT_AI_CONTINUE_PROMPT = """
        请根据客户原话给出简短、自然的回答。不要说明意图识别失败。客户原话：{{conversation.currentInput}}
        """;

    private final AiIntentApplicationService intentService;
    private final AiAgentApplicationService agentService;
    private final AiWorkflowTemplateResolver templateResolver;

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
        boolean matched = recognition.isMatched() && configured.contains(recognition.getIntentCode());
        String branch = matched ? recognition.getIntentCode() : "FALLBACK";
        String output = matched
            ? "识别意图：" + recognition.getIntentName() + "，置信度：" + recognition.getConfidence()
            : "未命中已配置意图";
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("conversation.intentCode", branch);
        int clarifyCount = matched ? 0 : clarifyCount(context.variables().get("workflow.clarifyCount")) + 1;
        String retryVariable = "workflow.intentRetry." + context.node().path("id").asText("unknown");
        int nodeRetryCount = matched ? 0 : clarifyCount(context.variables().get(retryVariable)) + 1;
        updates.put("workflow.clarifyCount", clarifyCount);
        updates.put(retryVariable, nodeRetryCount);
        if (matched) return new AiWorkflowNodeResult("CONTINUE", branch, output, null, updates);

        var config = context.node().path("config");
        boolean inherited = "INHERIT".equals(config.path("fallbackMode").asText("EDGE"));
        Map<String, Object> commonPolicy = commonPolicy(context);
        String fallbackMode = inherited ? text(commonPolicy, "fallbackMode", "EDGE") : config.path("fallbackMode").asText("EDGE");
        int maxRetries = inherited ? integer(commonPolicy, "maxRetries", 2) : config.path("maxRetries").asInt(2);
        if (RETRY_MODES.contains(fallbackMode) && nodeRetryCount > maxRetries) {
            String limitAction = inherited ? text(commonPolicy, "retryLimitAction", "CONTINUE")
                : config.path("retryLimitAction").asText("CONTINUE");
            String limitText = resolve(value(config, commonPolicy, inherited, "limitText", ""), context);
            if (!Set.of("EDGE", "CONTINUE").contains(limitAction)) {
                return directAction(limitAction, limitText,
                    value(config, commonPolicy, inherited, "limitQueueCode", ""),
                    value(config, commonPolicy, inherited, "limitExtension", ""), updates);
            }
            String limitBranch = "EDGE".equals(limitAction) ? "FALLBACK" : "CONTINUE";
            return limitText.isBlank()
                ? new AiWorkflowNodeResult("CONTINUE", limitBranch, "未命中次数已达到上限", null, updates)
                : new AiWorkflowNodeResult("SPEAK", limitBranch, limitText, "TTS", updates);
        }
        return switch (fallbackMode) {
            case "FIXED_RETRY" -> fixedFallback(context,
                value(config, commonPolicy, inherited, "fallbackText", ""), "SPEAK_RETRY", null, updates);
            case "FIXED_CONTINUE" -> fixedFallback(context,
                value(config, commonPolicy, inherited, "fallbackText", ""), "SPEAK", "CONTINUE", updates);
            case "AI_RETRY" -> aiFallback(context,
                StringUtils.blankToDefault(value(config, commonPolicy, inherited, "fallbackPrompt", ""), DEFAULT_AI_RETRY_PROMPT), true, updates);
            case "AI_CONTINUE" -> aiFallback(context,
                StringUtils.blankToDefault(value(config, commonPolicy, inherited, "fallbackPrompt", ""), DEFAULT_AI_CONTINUE_PROMPT), false, updates);
            default -> new AiWorkflowNodeResult("CONTINUE", "FALLBACK", output, null, updates);
        };
    }

    private AiWorkflowNodeResult directAction(String action, String speech, String queueCode, String extension,
                                              Map<String, Object> updates) {
        String target = switch (action) {
            case "TRANSFER_QUEUE" -> queueCode;
            case "TRANSFER_EXTENSION" -> extension;
            default -> null;
        };
        return speech.isBlank()
            ? new AiWorkflowNodeResult("DIRECT_ACTION", null, target, action, updates)
            : new AiWorkflowNodeResult("SPEAK_ACTION", target, speech, action, updates);
    }

    private AiWorkflowNodeResult fixedFallback(AiWorkflowNodeContext context, String template, String status,
                                               String branch, Map<String, Object> updates) {
        String text = resolve(template, context);
        if (text.isBlank()) throw new ServiceException("意图未命中固定话术不能为空");
        return new AiWorkflowNodeResult(status, branch, text, "TTS", updates);
    }

    private AiWorkflowNodeResult aiFallback(AiWorkflowNodeContext context, String template, boolean retry,
                                            Map<String, Object> updates) {
        String prompt = resolve(template, context);
        if (prompt.isBlank()) throw new ServiceException("意图未命中 AI 提示词不能为空");
        String status = retry ? "STREAM_AI_RETRY" : "STREAM_AI";
        String branch = retry ? null : "CONTINUE";
        if (StringUtils.startsWith(context.channelType(), "VOICE_")) {
            return new AiWorkflowNodeResult(status, branch, prompt, "MODEL_REPLY", updates);
        }
        Long conversationId = longValue(context.variables().get("ai.conversationId"));
        AiChatTurnResult turn = agentService.chatOnceModel(context.aiAgentId(), conversationId, prompt);
        if (turn.conversationId() != null) updates.put("ai.conversationId", turn.conversationId());
        updates.put("ai.answerSource", turn.sourceType());
        updates.put("ai.lastAnswer", turn.answer());
        return new AiWorkflowNodeResult(retry ? "SPEAK_RETRY" : "SPEAK", branch, turn.answer(), "TTS", updates);
    }

    private String resolve(String template, AiWorkflowNodeContext context) {
        return templateResolver.resolve(template, context.variables()).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> commonPolicy(AiWorkflowNodeContext context) {
        Object value = context.workflowSettings().get("intentFallback");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String value(com.fasterxml.jackson.databind.JsonNode config, Map<String, Object> commonPolicy,
                         boolean inherited, String key, String defaultValue) {
        return inherited ? text(commonPolicy, key, defaultValue) : config.path(key).asText(defaultValue);
    }

    private String text(Map<String, Object> values, String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private int integer(Map<String, Object> values, String key, int defaultValue) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
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
