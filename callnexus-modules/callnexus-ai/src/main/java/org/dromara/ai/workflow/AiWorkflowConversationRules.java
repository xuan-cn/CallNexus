package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.ai.workflow.handler.DialogueStepNodeHandler;
import org.dromara.common.core.exception.ServiceException;
import java.util.*;

/** Rule configuration and explicit event matching. */
public final class AiWorkflowConversationRules {
    private AiWorkflowConversationRules() { }
    public static final Set<String> EVENTS = Set.of("TRANSFER", "DECLINE", "LATER", "SILENCE", "INVALID", "OUT_OF_SCOPE");
    public static final Set<String> TEXT_EVENTS = Set.of("TRANSFER", "DECLINE", "LATER");
    public static String label(String event) {
        return switch (event) { case "TRANSFER" -> "要求转人工"; case "DECLINE" -> "明确拒绝"; case "LATER" -> "稍后联系"; case "SILENCE" -> "沉默超时"; case "INVALID" -> "连续无效回答"; case "OUT_OF_SCOPE" -> "超出业务范围"; default -> event; };
    }
    public static JsonNode effective(JsonNode definition, JsonNode node) {
        String mode = node.path("config").path("conversationRuleMode").asText("INHERIT");
        if ("DISABLED".equals(mode)) return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        return "OVERRIDE".equals(mode) ? node.path("config").path("conversationRules") : definition.path("settings").path("conversationRules");
    }
    public static void validate(JsonNode rules) {
        if (rules.isMissingNode()) return;
        if (!rules.isArray() || rules.size() > 6) throw new ServiceException("公共对话规则必须是数组，最多 6 项");
        Set<String> events = new HashSet<>();
        Set<String> phrases = new HashSet<>();
        for (var rule : rules) {
            String event = rule.path("event").asText("");
            if (!EVENTS.contains(event) || !events.add(event)) throw new ServiceException("公共对话规则类型无效或重复");
            if (!rule.path("enabled").asBoolean(false)) continue;
            if (rule.path("phrases").asText("").length() > 2000 || rule.path("replyText").asText("").length() > 1000 || rule.path("retryText").asText("").length() > 500) throw new ServiceException("公共规则话术过长");
            if (!Set.of("HANGUP", "TRANSFER_QUEUE").contains(rule.path("action").asText("HANGUP"))) throw new ServiceException("公共规则动作只能为挂断或转人工");
            if ("TRANSFER_QUEUE".equals(rule.path("action").asText()) && rule.path("queueCode").asText("").isBlank()) throw new ServiceException("公共规则转人工必须选择技能组");
            if (rule.path("maxCount").asInt(2) < 1 || rule.path("maxCount").asInt(2) > 10) throw new ServiceException("公共规则次数应为 1 到 10");
            if (TEXT_EVENTS.contains(event)) {
                String mode = rule.path("matchMode").asText("EXACT");
                if (!Set.of("EXACT", "KEYWORD", "INTENT").contains(mode)) throw new ServiceException("公共规则匹配方式无效");
                if ("INTENT".equals(mode)) {
                    if (!rule.path("intentCodes").isArray() || rule.path("intentCodes").isEmpty()) throw new ServiceException("公共规则意图匹配至少选择一个意图");
                    for (var code : rule.path("intentCodes")) if (!code.isTextual() || code.asText().isBlank()) throw new ServiceException("公共规则意图编码无效");
                }
                if (!Set.of("ANY", "ALL").contains(rule.path("keywordMode").asText("ANY")) || rule.path("excludedKeywords").asText("").length() > 1000) throw new ServiceException("公共规则关键词条件无效或排除词过长");
                var answers = DialogueStepNodeHandler.answers(rule.path("phrases").asText(""));
                if (!"INTENT".equals(mode) && answers.isEmpty() && ("KEYWORD".equals(mode) || !rule.path("aiUnderstandingEnabled").asBoolean(false))) throw new ServiceException("启用的公共规则必须填写话术/关键词，或开启 AI 辅助理解");
                if ("EXACT".equals(mode)) for (String answer : answers) if (!phrases.add(answer)) throw new ServiceException("公共规则的明确话术不能互相重复");
            }
        }
    }
    public static JsonNode match(JsonNode rules, String event, String text) {
        for (var rule : rules) {
            if (!rule.path("enabled").asBoolean(false)) continue;
            String type = rule.path("event").asText();
            if (event != null ? type.equals(event) : Set.of("TRANSFER", "DECLINE", "LATER").contains(type)
                && DialogueStepNodeHandler.answers(rule.path("phrases").asText("")).contains(DialogueStepNodeHandler.normalize(text))) return rule;
        }
        return null;
    }
}
