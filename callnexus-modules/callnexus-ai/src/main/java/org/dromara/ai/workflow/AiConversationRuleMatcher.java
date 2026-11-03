package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class AiConversationRuleMatcher {
    private final DialogueAnswerMatcher matcher;
    private final AiDialogueUnderstandingService understanding;
    private final AiWorkflowTemplateResolver resolver;
    public record Match(JsonNode rule, Map<String, Object> details) { }

    public Match match(JsonNode rules, JsonNode node, String text, Long agentId, Map<String, Object> variables) {
        var config = JsonNodeFactory.instance.objectNode();
        var options = config.putArray("answerOptions");
        Map<String, JsonNode> enabled = new LinkedHashMap<>();
        for (var rule : rules) {
            String event = rule.path("event").asText();
            if (!rule.path("enabled").asBoolean(false) || !AiWorkflowConversationRules.TEXT_EVENTS.contains(event)) continue;
            enabled.put(event, rule);
            var option = options.addObject().put("code", event).put("label", AiWorkflowConversationRules.label(event)).put("answers", rule.path("phrases").asText(""));
            for (String key : List.of("matchMode", "keywordMode", "excludedKeywords", "intentCodes")) if (rule.has(key)) option.set(key, rule.get(key));
        }
        if (enabled.isEmpty() || text == null || text.isBlank()) return new Match(null, Map.of());
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Long> timings = new LinkedHashMap<>();
        details.put("timings", timings);
        details.put("aiCalled", false);
        long started = System.nanoTime();
        var result = matcher.match(config, text, agentId);
        timings.put("matching", (System.nanoTime() - started) / 1_000_000);
        if (result.intentMs() != null) timings.put("intent", result.intentMs());
        details.put("matchReason", result.reason());
        details.put("matchedLabels", result.matchedBranches().stream().map(branch -> AiWorkflowConversationRules.label(branch.replaceFirst("^OPTION_", ""))).toList());
        details.put("confidence", result.confidence());
        if (result.branch() != null && result.branch().startsWith("OPTION_")) {
            String event = result.branch().substring(7);
            if (enabled.containsKey(event)) {
                details.put("reason", result.reason());
                details.put("evidence", text.substring(0, Math.min(200, text.length())));
                return new Match(enabled.get(event), details);
            }
        }
        details.put("reason", result.reason());
        var aiOptions = enabled.entrySet().stream().filter(entry -> entry.getValue().path("aiUnderstandingEnabled").asBoolean(false))
            .map(entry -> new AiDialogueUnderstandingService.Option(entry.getKey(), AiWorkflowConversationRules.label(entry.getKey()), entry.getValue().path("phrases").asText(""))).toList();
        if (aiOptions.isEmpty()) return new Match(null, details);
        if (agentId == null) { details.put("reason", "AI_UNAVAILABLE"); return new Match(null, details); }
        started = System.nanoTime();
        details.put("aiCalled", true);
        try {
            String question = resolver.resolve(node.path("config").path("text").asText(""), variables);
            if (question.isBlank() && variables.get("workflow.ruleQuestion") instanceof String previous) question = previous;
            var decision = understanding.classifyRules(agentId, question, text, aiOptions);
            if (decision == null || !Double.isFinite(decision.confidence()) || decision.confidence() < 0 || decision.confidence() > 1
                || decision.evidence() == null || decision.evidence().isBlank() || !text.contains(decision.evidence())) {
                details.put("reason", "AI_FAILED");
                return new Match(null, details);
            }
            details.put("confidence", decision.confidence());
            details.put("evidence", decision.evidence().substring(0, Math.min(200, decision.evidence().length())));
            boolean allowed = aiOptions.stream().anyMatch(option -> option.code().equals(decision.optionCode()));
            if ("SELECT".equals(decision.kind()) && allowed && decision.confidence() >= .95) {
                details.put("reason", "AI_MATCH");
                return new Match(enabled.get(decision.optionCode()), details);
            }
            details.put("reason", "SELECT".equals(decision.kind()) && allowed ? "LOW_CONFIDENCE" : "AI_NO_MATCH");
        } catch (Exception exception) {
            details.put("reason", "AI_FAILED");
        } finally {
            timings.put("understanding", (System.nanoTime() - started) / 1_000_000);
        }
        return new Match(null, details);
    }
}
