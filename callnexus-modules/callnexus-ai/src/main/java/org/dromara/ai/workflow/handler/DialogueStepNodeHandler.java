package org.dromara.ai.workflow.handler;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.workflow.*;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DialogueStepNodeHandler implements AiWorkflowNodeHandler {
    private final AiWorkflowTemplateResolver resolver;
    private final AiDialogueUnderstandingService understanding;
    private final org.dromara.ai.service.AiAgentApplicationService agentService;
    private final AiWorkflowSlotExtractionService extraction;
    private final DialogueAnswerMatcher matcher;

    @Override
    public Set<String> nodeTypes() { return Set.of("DIALOGUE_STEP"); }

    public static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\p{P}\\p{Z}\\s]+", "").toLowerCase(Locale.ROOT);
    }

    public static Set<String> answers(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.split("\\R")).map(DialogueStepNodeHandler::normalize)
            .filter(value -> !value.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        var config = context.node().path("config");
        String key = "workflow.dialogue." + context.node().path("id").asText();
        Map<String, Object> updates = new LinkedHashMap<>();
        var fields = fieldTargets(context);
        String answerMode = AiWorkflowAnswerPolicy.mode(config, false);
        var options = options(config);
        updates.put(key + ".aiCalled", false);
        updates.put(key + ".reason", "ASKING");
        updates.put(key + ".optionCode", "");
        updates.put(key + ".limitReached", false);
        if (!Boolean.TRUE.equals(context.variables().get(key + ".waiting"))) {
            if (options.isEmpty() && config.path("reuseCollectedFields").asBoolean(false) && !fields.isEmpty() && missing(fields, context.variables()).isEmpty()) {
                updates.put(key + ".reason", "FIELDS_REUSED");
                updates.put(key + ".waiting", false);
                updates.put(key + ".retries", 0);
                updates.put(key + ".questions", 0);
                return new AiWorkflowNodeResult("CONTINUE", "COMPLETE", "复用已收集的流程字段", null, updates);
            }
            updates.put(key + ".waiting", true);
            updates.put(key + ".retries", 0);
            updates.put(key + ".questions", 0);
            return speech(context, config.path("text").asText(), updates);
        }
        String input = normalize(context.currentInput());
        long matchStarted = System.nanoTime();
        var match = matcher.match(config, context.currentInput(), context.aiAgentId());
        String branch = match.branch();
        updates.put(key + ".matchReason", match.reason());
        updates.put(key + ".matchedBranches", match.matchedBranches());
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("matching", (System.nanoTime() - matchStarted) / 1_000_000);
        if (match.intentMs() != null) timings.put("intent", match.intentMs());
        updates.put(key + ".timings", timings);
        if (branch != null && branch.startsWith("OPTION_")) updates.put(key + ".optionCode", branch.substring("OPTION_".length()));
        int retries = context.variables().get(key + ".retries") instanceof Number value ? value.intValue() : 0;
        updates.put(key + ".decision", branch == null ? "UNCLEAR" : match.reason().replace("_MATCH", ""));
        updates.put(key + ".evidence", branch == null ? "" : context.currentInput());
        updates.put(key + ".confidence", match.confidence());
        updates.put(key + ".reason", branch == null && "NO_MATCH".equals(match.reason()) ? "AI_DISABLED" : match.reason());
        String stage = "CLASSIFICATION";
        long stageStarted = System.nanoTime();
        if (branch == null && !input.isBlank() && config.path("aiUnderstandingEnabled").asBoolean(false)) {
            try {
                updates.put(key + ".aiCalled", true);
                var decision = options.isEmpty() ? understanding.classify(context.aiAgentId(), config.path("goal").asText(),
                    resolver.resolve(config.path("text").asText(), context.variables()), context.currentInput())
                    : understanding.classify(context.aiAgentId(), config.path("goal").asText(),
                        resolver.resolve(config.path("text").asText(), context.variables()), context.currentInput(), options);
                timings.put("understanding", (System.nanoTime() - stageStarted) / 1_000_000);
                updates.put(key + ".decision", decision.kind());
                updates.put(key + ".evidence", decision.evidence());
                updates.put(key + ".confidence", decision.confidence());
                updates.put(key + ".optionCode", decision.optionCode());
                boolean eligible = "DECLINE".equals(decision.kind()) || (options.isEmpty() && "CONFIRM".equals(decision.kind()))
                    || ("SELECT".equals(decision.kind()) && options.stream().anyMatch(option -> option.code().equals(decision.optionCode())));
                updates.put(key + ".reason", decision.confidence() < 0.9 ? "LOW_CONFIDENCE"
                    : eligible && !config.path("aiAutoAdvance").asBoolean(false) ? "AUTO_ADVANCE_DISABLED"
                    : "QUESTION".equals(decision.kind()) && "OFF".equals(answerMode) ? "KNOWLEDGE_DISABLED" : "NEEDS_CLARIFICATION");
                if (!fields.isEmpty() && decision.confidence() >= 0.9 && Set.of("CONFIRM", "CORRECTION", "SELECT").contains(decision.kind())) {
                    stage = "EXTRACTION";
                    stageStarted = System.nanoTime();
                    Map<String, Object> values = extraction.extract(context.aiAgentId(), context.currentInput(), fields);
                    timings.put("extraction", (System.nanoTime() - stageStarted) / 1_000_000);
                    for (var field : fields) {
                        Object value = values.get(field.key());
                        // Only new, verbatim text values may populate temporary workflow fields.
                        if (!(context.variables().get(field.key()) instanceof String existing) || existing.isBlank()) {
                            if (value instanceof String text && !text.isBlank() && text.length() <= 500 && context.currentInput().contains(text)) {
                                updates.put(field.key(), text);
                                updates.put(key + ".fieldEvidence." + field.key(), text);
                            }
                        }
                    }
                }
                if (decision.confidence() >= 0.9 && config.path("aiAutoAdvance").asBoolean(false)) {
                    if (options.isEmpty() && "CONFIRM".equals(decision.kind())) branch = "COMPLETE";
                    if ("DECLINE".equals(decision.kind())) branch = "DECLINED";
                    if ("SELECT".equals(decision.kind()) && options.stream().anyMatch(option -> option.code().equals(decision.optionCode()))) branch = "OPTION_" + decision.optionCode();
                    if (branch != null) updates.put(key + ".reason", "AI_MATCH");
                }
                if ("QUESTION".equals(decision.kind()) && decision.confidence() >= 0.9 && !"OFF".equals(answerMode)) {
                    int count = context.variables().get(key + ".questions") instanceof Number value ? value.intValue() : 0;
                    if (count >= 5) branch = "LIMIT";
                    else {
                        stage = "KNOWLEDGE";
                        stageStarted = System.nanoTime();
                        String answer = "当前资料不足以回答这个问题。";
                        var reply = "KNOWLEDGE_ONLY".equals(answerMode)
                            ? agentService.chatOnceKnowledgeOnly(context.aiAgentId(), null, context.currentInput())
                            : agentService.chatOnce(context.aiAgentId(), null, context.currentInput());
                        timings.put("knowledge", (System.nanoTime() - stageStarted) / 1_000_000);
                        if (AiWorkflowAnswerPolicy.accepts(answerMode, reply)) answer = reply.answer();
                        updates.put(key + ".questions", count + 1);
                        updates.put(key + ".reason", "KNOWLEDGE_RETRY");
                        return new AiWorkflowNodeResult("SPEAK_RETRY", null, answer + "\n" + resolver.resolve(config.path("retryText").asText(), context.variables()), "TTS", updates);
                    }
                }
            } catch (Exception exception) {
                timings.put(switch (stage) { case "EXTRACTION" -> "extraction"; case "KNOWLEDGE" -> "knowledge"; default -> "understanding"; }, (System.nanoTime() - stageStarted) / 1_000_000);
                branch = null;
                updates.put(key + ".reason", stage + "_FAILED");
                updates.put(key + ".decision", "UNAVAILABLE");
                updates.put(key + ".evidence", "");
                updates.put(key + ".confidence", 0D);
                // Do not speak provider errors, unvalidated JSON, or unsupported business answers.
            }
        }
        Map<String, Object> effective = new LinkedHashMap<>(context.variables());
        effective.putAll(updates);
        List<String> missing = missing(fields, effective);
        updates.put(key + ".missingFields", missing);
        if (("COMPLETE".equals(branch) || (branch != null && branch.startsWith("OPTION_"))) && !missing.isEmpty()) {
            branch = null;
            updates.put(key + ".reason", "MISSING_FIELDS");
        }
        if (branch == null && retries >= config.path("maxRetries").asInt(2)) {
            branch = "LIMIT";
            updates.put(key + ".limitReached", true);
        }
        if (branch != null) {
            updates.put(key + ".waiting", false);
            updates.put(key + ".retries", 0);
            updates.put(key + ".questions", 0);
            return new AiWorkflowNodeResult("CONTINUE", branch, "对话步骤结果：" + branchLabel(config, branch), null, updates);
        }
        updates.put(key + ".retries", retries + 1);
        return missing.isEmpty() ? speech(context, config.path("retryText").asText(), updates)
            : new AiWorkflowNodeResult("SPEAK_RETRY", null,
                "还需要确认：" + String.join("、", missing) + "，请您补充一下。", "TTS", updates);
    }

    public static List<AiDialogueUnderstandingService.Option> options(com.fasterxml.jackson.databind.JsonNode config) {
        DialogueAnswerMatcher.validate(config);
        var node = config.path("answerOptions");
        if (node.isMissingNode()) return List.of();
        if (!node.isArray() || node.size() > 10) throw new ServiceException("业务答案选项最多 10 项");
        List<AiDialogueUnderstandingService.Option> options = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        Set<String> exact = new HashSet<>("EXACT".equals(config.path("declinedMatchMode").asText("EXACT")) ? answers(config.path("declinedAnswers").asText("")) : Set.of());
        for (var item : node) {
            String code = item.path("code").asText("");
            String label = item.path("label").asText("").trim();
            String examples = item.path("answers").asText("");
            if (!code.matches("[A-Z][A-Z0-9_]{0,31}") || !codes.add(code) || label.isBlank() || label.length() > 100 || examples.length() > 2000) throw new ServiceException("业务答案编码、名称或示例无效/重复");
            if ("EXACT".equals(item.path("matchMode").asText("EXACT"))) for (String answer : answers(examples)) if (!exact.add(answer)) throw new ServiceException("业务答案示例与其他选项或拒绝回答重复");
            options.add(new AiDialogueUnderstandingService.Option(code, label, examples));
        }
        return options;
    }

    public static Set<String> branches(com.fasterxml.jackson.databind.JsonNode config) {
        Set<String> result = new HashSet<>(Set.of("DECLINED", "LIMIT"));
        var options = options(config);
        if (options.isEmpty()) result.add("COMPLETE");
        else options.forEach(option -> result.add("OPTION_" + option.code()));
        return result;
    }

    public static String branchLabel(com.fasterxml.jackson.databind.JsonNode config, String branch) {
        if (branch == null) return "";
        for (var option : config.path("answerOptions")) {
            if (branch.equals("OPTION_" + option.path("code").asText())) return option.path("label").asText(branch);
        }
        return switch (branch) { case "COMPLETE" -> "确认完成"; case "DECLINED" -> "明确拒绝"; case "LIMIT" -> "澄清上限"; default -> branch; };
    }

    private List<AiWorkflowSlotExtractionService.Target> fieldTargets(AiWorkflowNodeContext context) {
        List<AiWorkflowSlotExtractionService.Target> targets = new ArrayList<>();
        var fields = context.node().path("config").path("collectedFields");
        if (fields.isMissingNode()) return targets;
        if (!fields.isArray() || fields.size() > 10) throw new ServiceException("对话步骤最多选择 10 个流程文本字段");
        for (var field : fields) {
            String key = field.path("key").asText("");
            if (!key.startsWith("workflow.custom_") || !"STRING".equals(field.path("type").asText("STRING"))) throw new ServiceException("对话步骤仅允许收集流程临时文本字段");
            targets.add(new AiWorkflowSlotExtractionService.Target(key, field.path("label").asText(key), "STRING"));
        }
        return targets.stream().distinct().toList();
    }

    private List<String> missing(List<AiWorkflowSlotExtractionService.Target> fields, Map<String, Object> values) {
        return fields.stream().filter(field -> !(values.get(field.key()) instanceof String text) || text.isBlank())
            .map(AiWorkflowSlotExtractionService.Target::label).toList();
    }

    private AiWorkflowNodeResult speech(AiWorkflowNodeContext context, String template, Map<String, Object> updates) {
        Map<String, Object> effective = new LinkedHashMap<>(context.variables());
        effective.putAll(updates);
        String text = resolver.resolve(template, effective).trim();
        if (text.isBlank()) throw new ServiceException("对话步骤的提问或澄清话术不能为空");
        return new AiWorkflowNodeResult("SPEAK_RETRY", null, text, "TTS", updates);
    }
}
