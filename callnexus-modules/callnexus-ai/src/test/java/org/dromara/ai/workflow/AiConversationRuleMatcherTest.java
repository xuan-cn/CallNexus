package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.dromara.ai.service.AiIntentApplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiConversationRuleMatcherTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiDialogueUnderstandingService ai = mock(AiDialogueUnderstandingService.class);
    private final AiIntentApplicationService intents = mock(AiIntentApplicationService.class);
    private final AiConversationRuleMatcher matcher = new AiConversationRuleMatcher(new DialogueAnswerMatcher(intents), ai, new AiWorkflowTemplateResolver());
    private ArrayNode rules() {
        var rules = json.createArrayNode();
        rules.addObject().put("event", "TRANSFER").put("enabled", true).put("phrases", "转人工").put("aiUnderstandingEnabled", true);
        return rules;
    }
    private AiConversationRuleMatcher.Match run(ArrayNode rules, String input) {
        return matcher.match(rules, json.createObjectNode().set("config", json.createObjectNode().put("text", "您是否住院？")), input, 1L, Map.of());
    }
    @Test void exactRulesSkipModels() {
        assertEquals("TRANSFER", run(rules(), "转人工。").rule().path("event").asText());
        verifyNoInteractions(ai, intents);
    }
    @Test void keywordsRespectExclusions() {
        var rules = rules();
        ((com.fasterxml.jackson.databind.node.ObjectNode) rules.get(0)).put("matchMode", "KEYWORD").put("excludedKeywords", "不要转人工").put("aiUnderstandingEnabled", false);
        assertNull(run(rules, "不要转人工").rule());
        assertNotNull(run(rules, "请转人工").rule());
        verifyNoInteractions(ai);
    }
    @Test void semanticMatchRequiresThresholdAllowedOptionAndVerbatimEvidence() {
        when(ai.classifyRules(anyLong(), anyString(), anyString(), anyList())).thenReturn(new AiDialogueUnderstandingService.Decision("SELECT", .96, "找个真人", "TRANSFER"));
        assertNotNull(run(rules(), "找个真人").rule());
        when(ai.classifyRules(anyLong(), anyString(), anyString(), anyList())).thenReturn(new AiDialogueUnderstandingService.Decision("SELECT", .94, "找个真人", "TRANSFER"));
        assertNull(run(rules(), "找个真人").rule());
        when(ai.classifyRules(anyLong(), anyString(), anyString(), anyList())).thenReturn(new AiDialogueUnderstandingService.Decision("SELECT", 1, "找个真人", "LATER"));
        assertNull(run(rules(), "找个真人").rule());
        when(ai.classifyRules(anyLong(), anyString(), anyString(), anyList())).thenReturn(new AiDialogueUnderstandingService.Decision("SELECT", 1, "编造原文", "TRANSFER"));
        assertNull(run(rules(), "找个真人").rule());
    }
    @Test void errorsAndUnclearInputNeverTriggerAnAction() {
        when(ai.classifyRules(anyLong(), anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("timeout"));
        assertEquals("AI_FAILED", run(rules(), "不是本人").details().get("reason"));
        reset(ai);
        when(ai.classifyRules(anyLong(), anyString(), anyString(), anyList())).thenReturn(new AiDialogueUnderstandingService.Decision("UNCLEAR", .99, "不是不办理", ""));
        assertNull(run(rules(), "不是不办理").rule());
    }
    @Test void conflictsNeverSelectFirstRuleAndSilenceNeverCallsAi() {
        var rules = rules();
        rules.addObject().put("event", "DECLINE").put("enabled", true).put("matchMode", "KEYWORD").put("phrases", "转人工");
        assertNull(run(rules, "转人工").rule());
        assertEquals("MATCH_CONFLICT", run(rules, "转人工").details().get("matchReason"));
        var silence = json.createArrayNode();
        silence.addObject().put("event", "SILENCE").put("enabled", true).put("aiUnderstandingEnabled", true);
        assertTrue(run(silence, "我没说话").details().isEmpty());
    }
    @Test void validationRequiresIntentCandidatesAndAcceptsAiOnlyRules() {
        var rules = rules();
        var rule = (com.fasterxml.jackson.databind.node.ObjectNode) rules.get(0);
        rule.put("phrases", "");
        assertDoesNotThrow(() -> AiWorkflowConversationRules.validate(rules));
        rule.put("matchMode", "INTENT");
        assertThrows(Exception.class, () -> AiWorkflowConversationRules.validate(rules));
    }
}
