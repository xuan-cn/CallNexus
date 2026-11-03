package org.dromara.ai.workflow;

public interface AiDialogueUnderstandingService {
    Decision classify(Long agentId, String goal, String question, String input);
    Decision classifyRules(Long agentId, String question, String input, java.util.List<Option> options);
    default Decision classify(Long agentId, String goal, String question, String input, java.util.List<Option> options) {
        return classify(agentId, goal, question, input);
    }
    record Option(String code, String label, String answers) { }
    record Decision(String kind, double confidence, String evidence, String optionCode) {
        public Decision(String kind, double confidence, String evidence) { this(kind, confidence, evidence, ""); }
    }
}
