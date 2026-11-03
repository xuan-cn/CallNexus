package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.ai.workflow.handler.DialogueStepNodeHandler;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class DialogueAnswerMatcher {
    private final AiIntentApplicationService intents;
    public record Result(String branch, String reason, double confidence, List<String> matchedBranches, Long intentMs) {
        public Result(String branch, String reason) { this(branch, reason, branch == null ? 0D : 1D, branch == null ? List.of() : List.of(branch), null); }
    }
    private record Rule(String branch, JsonNode config) { }

    private static List<Rule> rules(JsonNode config) {
        List<Rule> rules = new ArrayList<>();
        if (config.path("answerOptions").isEmpty()) rules.add(new Rule("COMPLETE", flat(config, "accepted")));
        rules.add(new Rule("DECLINED", flat(config, "declined")));
        for (var option : config.path("answerOptions")) rules.add(new Rule("OPTION_" + option.path("code").asText(), option));
        return rules;
    }

    private static JsonNode flat(JsonNode config, String prefix) {
        ObjectNode rule = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        rule.put("answers", config.path(prefix + "Answers").asText(""));
        for (String key : List.of("MatchMode", "KeywordMode", "ExcludedKeywords", "IntentCodes")) {
            if (config.has(prefix + key)) rule.set(Character.toLowerCase(key.charAt(0)) + key.substring(1), config.get(prefix + key));
        }
        return rule;
    }

    public static void validate(JsonNode config) {
        for (var rule : rules(config)) {
            String mode = rule.config.path("matchMode").asText("EXACT");
            if (!Set.of("EXACT", "KEYWORD", "INTENT").contains(mode)) throw new ServiceException("回答匹配方式无效：" + rule.branch);
            if ("KEYWORD".equals(mode)) {
                if (!Set.of("ANY", "ALL").contains(rule.config.path("keywordMode").asText("ANY"))) throw new ServiceException("关键词匹配条件无效：" + rule.branch);
                if (DialogueStepNodeHandler.answers(rule.config.path("answers").asText("")).isEmpty()) throw new ServiceException("关键词不能为空：" + rule.branch);
            }
            if ("INTENT".equals(mode) && codes(rule.config).isEmpty()) throw new ServiceException("意图匹配至少选择一个意图：" + rule.branch);
        }
    }

    private static Set<String> codes(JsonNode config) {
        Set<String> result = new LinkedHashSet<>();
        if (config.path("intentCodes").isArray()) for (var code : config.path("intentCodes")) if (!code.asText("").isBlank()) result.add(code.asText());
        return result;
    }

    public Result match(JsonNode config, String text, Long agentId) {
        validate(config);
        String input = DialogueStepNodeHandler.normalize(text);
        if (input.isBlank()) return new Result(null, "NO_MATCH");
        List<Rule> rules = rules(config);
        Map<String, String> hits = new LinkedHashMap<>();
        for (var rule : rules) {
            String mode = rule.config.path("matchMode").asText("EXACT");
            var answers = DialogueStepNodeHandler.answers(rule.config.path("answers").asText(""));
            if ("EXACT".equals(mode) && answers.contains(input)) hits.put(rule.branch, "EXACT_MATCH");
            if ("KEYWORD".equals(mode) && !DialogueStepNodeHandler.answers(rule.config.path("excludedKeywords").asText("")).stream().anyMatch(input::contains)) {
                boolean matched = "ALL".equals(rule.config.path("keywordMode").asText("ANY"))
                    ? answers.stream().allMatch(input::contains) : answers.stream().anyMatch(input::contains);
                if (matched) hits.put(rule.branch, "KEYWORD_MATCH");
            }
        }
        if (hits.size() > 1) return new Result(null, "MATCH_CONFLICT", 0D, List.copyOf(hits.keySet()), null);
        if (hits.size() == 1) return new Result(hits.keySet().iterator().next(), hits.values().iterator().next());
        Set<String> configured = new LinkedHashSet<>();
        for (var rule : rules) if ("INTENT".equals(rule.config.path("matchMode").asText())) configured.addAll(codes(rule.config));
        if (configured.isEmpty()) return new Result(null, "NO_MATCH");
        double confidence = 0D;
        long started = System.nanoTime();
        try {
            if (agentId == null) return new Result(null, "INTENT_FAILED");
            var request = new AiIntentRecognitionRequest();
            request.setAgentId(agentId);
            request.setText(text);
            request.setIntentCodes(new ArrayList<>(configured));
            var recognition = intents.recognize(request);
            if (recognition != null && recognition.isMatched() && configured.contains(recognition.getIntentCode())) {
                confidence = recognition.getConfidence() == null ? 0D : recognition.getConfidence().doubleValue();
                for (var rule : rules) if ("INTENT".equals(rule.config.path("matchMode").asText()) && codes(rule.config).contains(recognition.getIntentCode())) hits.put(rule.branch, "INTENT_MATCH");
            }
        } catch (Exception exception) {
            return new Result(null, "INTENT_FAILED", 0D, List.of(), (System.nanoTime() - started) / 1_000_000);
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return hits.size() > 1 ? new Result(null, "MATCH_CONFLICT", 0D, List.copyOf(hits.keySet()), elapsed) : hits.size() == 1
            ? new Result(hits.keySet().iterator().next(), "INTENT_MATCH", confidence, List.copyOf(hits.keySet()), elapsed)
            : new Result(null, "NO_MATCH", 0D, List.of(), elapsed);
    }
}
