package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.ai.service.impl.AiDialogueUnderstandingServiceImpl;
import org.dromara.ai.workflow.handler.DialogueStepNodeHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiWorkflowDialogueUnderstandingTest {
    private final AiDialogueUnderstandingService classifier = mock(AiDialogueUnderstandingService.class);
    private final AiAgentApplicationService agent = mock(AiAgentApplicationService.class);
    private final AiWorkflowSlotExtractionService extraction = mock(AiWorkflowSlotExtractionService.class);
    private final DialogueStepNodeHandler handler = new DialogueStepNodeHandler(new AiWorkflowTemplateResolver(), classifier, agent, extraction, new DialogueAnswerMatcher(org.mockito.Mockito.mock(org.dromara.ai.service.AiIntentApplicationService.class)));
    private final Map<String, Object> variables = new HashMap<>();
    private final ObjectNode node = new ObjectMapper().createObjectNode();
    private final ObjectNode config = node.put("id", "q").put("type", "DIALOGUE_STEP").putObject("config");

    private AiWorkflowNodeResult run(String input) {
        var result = handler.execute(new AiWorkflowNodeContext(node, variables, input, 8L));
        variables.putAll(result.variableUpdates());
        return result;
    }
    private void begin() {
        config.put("text", "需要办理吗？").put("goal", "确认办理需求").put("acceptedAnswers", "是的").put("declinedAnswers", "不是")
            .put("retryText", "请明确确认是否办理").put("maxRetries", 2).put("aiUnderstandingEnabled", true);
        run("");
    }
    private void decision(String kind, double confidence) {
        when(classifier.classify(anyLong(), anyString(), anyString(), anyString()))
            .thenAnswer(call -> new AiDialogueUnderstandingService.Decision(kind, confidence, call.getArgument(3)));
    }
    @Test void exactAnswersDoNotCallAi() {
        begin();
        assertEquals("COMPLETE", run("是的。").branchValue());
        verifyNoInteractions(classifier, agent);
    }
    @Test void keywordConflictsFallBackToAiRatherThanFirstBranch() {
        begin();
        config.put("declinedMatchMode", "KEYWORD").put("declinedAnswers", "是的").put("aiAutoAdvance", true);
        decision("DECLINE", .98);
        assertEquals("DECLINED", run("是的").branchValue());
        assertEquals("MATCH_CONFLICT", variables.get("workflow.dialogue.q.matchReason"));
        assertEquals("AI_MATCH", variables.get("workflow.dialogue.q.reason"));
        assertTrue(((Map<?, ?>) variables.get("workflow.dialogue.q.timings")).containsKey("understanding"));
    }
    @Test void keywordConflictsWithoutAiOnlyClarify() {
        begin();
        config.put("declinedMatchMode", "KEYWORD").put("declinedAnswers", "是的").put("aiUnderstandingEnabled", false);
        var result = run("是的");
        assertNull(result.branchValue());
        assertEquals("MATCH_CONFLICT", variables.get("workflow.dialogue.q.reason"));
        verifyNoInteractions(classifier);
    }
    @Test void semanticAdvanceRequiresExplicitSwitchAndThreshold() {
        begin(); decision("CONFIRM", .98);
        assertEquals("SPEAK_RETRY", run("我想申请这项服务").status());
        config.put("aiAutoAdvance", true);
        assertEquals("COMPLETE", run("麻烦帮我办理").branchValue());
    }
    @Test void lowConfidenceNeverAdvances() {
        begin(); config.put("aiAutoAdvance", true); decision("CONFIRM", .5);
        assertEquals("SPEAK_RETRY", run("可能需要").status());
    }
    @Test void knowledgeAnswerStaysAtStepWithoutCountingClarification() {
        begin(); config.put("knowledgeQuestionsEnabled", true); decision("QUESTION", .98);
        when(agent.chatOnceKnowledgeOnly(8L, null, "怎么上传材料")).thenReturn(new AiChatTurnResult(1L, "按短信指引操作。", "FAQ_EXACT", Map.of("hit", true, "fallback", false)));
        var result = run("怎么上传材料");
        assertEquals("SPEAK_RETRY", result.status());
        assertTrue(result.output().startsWith("按短信指引操作。"));
        assertTrue(result.output().endsWith("请明确确认是否办理"));
        assertEquals(0, variables.get("workflow.dialogue.q.retries"));
        assertNull(result.branchValue());
    }
    @Test void unsupportedModelAnswerIsNotSpoken() {
        begin(); config.put("knowledgeQuestionsEnabled", true); decision("QUESTION", .98);
        when(agent.chatOnceKnowledgeOnly(anyLong(), isNull(), anyString())).thenReturn(new AiChatTurnResult(1L, "编造的保证", "MODEL", Map.of("hit", false, "fallback", true)));
        assertFalse(run("费用多少").output().contains("编造"));
    }
    @Test void providerErrorsReturnAuthoredClarificationNotTechnicalError() {
        begin(); when(classifier.classify(anyLong(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("api-key-secret"));
        var result = run("我没听明白");
        assertEquals("请明确确认是否办理", result.output());
        assertEquals("UNAVAILABLE", variables.get("workflow.dialogue.q.decision"));
        assertEquals("", variables.get("workflow.dialogue.q.evidence"));
        assertTrue(((Map<?, ?>) variables.get("workflow.dialogue.q.timings")).containsKey("understanding"));
    }
    @Test void parserRejectsUnsupportedInstructionsAndInventedEvidence() throws Exception {
        assertEquals("CONFIRM", AiDialogueUnderstandingServiceImpl.parse("{\"kind\":\"CONFIRM\",\"confidence\":0.95,\"evidence\":\"需要办理\"}", "我需要办理").kind());
        for (String invalid : List.of(
            "{\"kind\":\"HANGUP\",\"confidence\":1,\"evidence\":\"需要办理\"}",
            "{\"kind\":\"CONFIRM\",\"confidence\":2,\"evidence\":\"需要办理\"}",
            "{\"kind\":\"CONFIRM\",\"confidence\":1,\"evidence\":\"同意\"}",
            "{\"kind\":\"CONFIRM\",\"confidence\":1,\"evidence\":\"\"}",
            "{\"kind\":\"CONFIRM\",\"confidence\":1,\"evidence\":\"需要办理\",\"node\":\"end\"}",
            "{\"kind\":\"CONFIRM\",\"kind\":\"DECLINE\",\"confidence\":1,\"evidence\":\"需要办理\"}",
            "{\"kind\":\"CONFIRM\",\"confidence\":1,\"evidence\":\"需要办理\"} {}")) {
            assertThrows(Exception.class, () -> AiDialogueUnderstandingServiceImpl.parse(invalid, "我需要办理"));
        }
    }
    @Test void classifierUsesBoundedCopiedProviderWithoutMutatingSharedConfig() {
        var agents = mock(org.dromara.ai.mapper.AiAgentMapper.class);
        var models = mock(org.dromara.ai.mapper.AiModelMapper.class);
        var providers = mock(org.dromara.ai.mapper.AiModelProviderMapper.class);
        var registry = mock(org.dromara.ai.provider.ChatProviderRegistry.class);
        var provider = new org.dromara.ai.domain.AiModelProvider();
        provider.setId(3L); provider.setEnabled(true); provider.setProviderType("TEST");
        provider.setConnectTimeoutSeconds(30); provider.setReadTimeoutSeconds(120);
        var model = new org.dromara.ai.domain.AiModel();
        model.setEnabled(true); model.setCapability("CHAT"); model.setProviderId(3L);
        var assistant = new org.dromara.ai.domain.AiAgent();
        assistant.setEnabled(true); assistant.setChatModelId(2L);
        when(agents.selectById(8L)).thenReturn(assistant);
        when(models.selectById(2L)).thenReturn(model);
        when(providers.selectById(3L)).thenReturn(provider);
        var chat = mock(org.dromara.ai.provider.ChatProvider.class);
        when(registry.get("TEST")).thenReturn(chat);
        when(chat.chat(any())).thenReturn(new org.dromara.ai.provider.ChatResult("{\"kind\":\"CONFIRM\",\"confidence\":0.95,\"evidence\":\"我要办理\"}", 0, 0));
        var service = new AiDialogueUnderstandingServiceImpl(agents, models, providers, registry);
        assertEquals("CONFIRM", service.classify(8L, "确认办理", "需要办理吗", "我要办理").kind());
        var captured = org.mockito.ArgumentCaptor.forClass(org.dromara.ai.provider.ChatRequest.class);
        verify(chat, times(1)).chat(captured.capture());
        assertEquals(3, captured.getValue().provider().getConnectTimeoutSeconds());
        assertEquals(6, captured.getValue().provider().getReadTimeoutSeconds());
        assertEquals(120, provider.getReadTimeoutSeconds());
        assertEquals(256, captured.getValue().maxTokens());
    }
    private void collectField() {
        config.putArray("collectedFields").addObject().put("key", "workflow.custom_hospital").put("label", "医院").put("type", "STRING");
    }
    @Test void requiredFieldBlocksFastConfirmationUntilExplicitTextIsCollected() {
        begin(); collectField();
        assertEquals("SPEAK_RETRY", run("是的").status());
        decision("CONFIRM", .99);
        when(extraction.extract(anyLong(), anyString(), anyList())).thenReturn(Map.of("workflow.custom_hospital", "北京医院", "customer.name", "张三"));
        var result = run("我在北京医院");
        assertEquals("北京医院", variables.get("workflow.custom_hospital"));
        assertFalse(variables.containsKey("customer.name"));
        assertEquals("SPEAK_RETRY", result.status());
        assertEquals("COMPLETE", run("是的").branchValue());
    }
    @Test void inventedFieldsDoNotSatisfyCompletionAndExistingFieldsAreNotOverwritten() {
        begin(); collectField(); config.put("aiAutoAdvance", true); decision("CONFIRM", .99);
        when(extraction.extract(anyLong(), anyString(), anyList())).thenReturn(Map.of("workflow.custom_hospital", "上海医院"));
        assertEquals("SPEAK_RETRY", run("我在北京医院").status());
        assertFalse(variables.containsKey("workflow.custom_hospital"));
        variables.put("workflow.custom_hospital", "已确认医院");
        run("我在上海医院");
        assertEquals("已确认医院", variables.get("workflow.custom_hospital"));
    }
    @Test void explicitReuseSkipsQuestionOnlyWhenFieldsAlreadyExist() {
        begin(); collectField(); run("不是");
        config.put("reuseCollectedFields", true);
        variables.put("workflow.custom_hospital", "北京医院");
        assertEquals("COMPLETE", run("").branchValue());
        verifyNoInteractions(extraction);
    }
    @Test void clarificationOnlyAsksForMissingFields() {
        begin(); collectField();
        config.withArray("collectedFields").addObject().put("key", "workflow.custom_department").put("label", "科室").put("type", "STRING");
        variables.put("workflow.custom_hospital", "北京医院");
        var result = run("是的");
        assertEquals("还需要确认：科室，请您补充一下。", result.output());
        assertEquals(List.of("科室"), variables.get("workflow.dialogue.q.missingFields"));
        assertFalse(result.output().contains("医院"));
        verifyNoInteractions(extraction);
    }
    private void businessOptions() {
        config.putArray("answerOptions").addObject().put("code", "SELF").put("label", "本人").put("answers", "我就是本人");
        config.withArray("answerOptions").addObject().put("code", "FAMILY").put("label", "家属").put("answers", "我是家属");
    }
    @Test void disabledAnswerModeDoesNotCallAnswerServiceEvenWithLegacySwitchOn() {
        begin(); config.put("knowledgeQuestionsEnabled", true).put("answerMode", "OFF"); decision("QUESTION", .99);
        run("怎么上传材料");
        verifyNoInteractions(agent);
        assertEquals("KNOWLEDGE_DISABLED", variables.get("workflow.dialogue.q.reason"));
    }
    @Test void modelFallbackAnswerStaysAtCurrentStepAndNeverBecomesConfirmation() {
        begin(); config.put("answerMode", "MODEL_FALLBACK").put("aiAutoAdvance", true); decision("QUESTION", .99);
        when(agent.chatOnce(8L, null, "怎么上传材料")).thenReturn(new AiChatTurnResult(1L, "模型给出的说明", "MODEL", Map.of("hit", false, "fallback", true)));
        var result = run("怎么上传材料");
        assertTrue(result.output().startsWith("模型给出的说明")); assertNull(result.branchValue());
        assertEquals("SPEAK_RETRY", result.status());
        verify(agent, never()).chatOnceKnowledgeOnly(anyLong(), any(), anyString());
    }
    @Test void answerPolicyDefaultsStayConservativeAndRejectInvalidValues() {
        assertEquals("OFF", AiWorkflowAnswerPolicy.mode(config, false));
        assertEquals("KNOWLEDGE_ONLY", AiWorkflowAnswerPolicy.mode(config, true));
        config.put("answerMode", "ARBITRARY");
        assertThrows(Exception.class, () -> AiWorkflowAnswerPolicy.mode(config, false));
    }
    @Test void semanticBusinessAnswerSelectsOnlyConfiguredBranch() {
        begin(); businessOptions(); config.put("aiAutoAdvance", true);
        when(classifier.classify(anyLong(), anyString(), anyString(), anyString(), anyList()))
            .thenReturn(new AiDialogueUnderstandingService.Decision("SELECT", .98, "我自己", "SELF"));
        var result = run("给我自己申请");
        assertEquals("OPTION_SELF", result.branchValue());
        assertEquals(true, variables.get("workflow.dialogue.q.aiCalled"));
        assertEquals("AI_MATCH", variables.get("workflow.dialogue.q.reason"));
    }
    @Test void exactBusinessAnswerBypassesAiAndGenericYesDoesNotCompleteIdentity() {
        begin(); businessOptions();
        assertEquals("OPTION_FAMILY", run("我是家属。").branchValue());
        assertEquals(false, variables.get("workflow.dialogue.q.aiCalled"));
        verifyNoInteractions(classifier);
        run("");
        assertEquals("SPEAK_RETRY", run("是的").status());
    }
    @Test void businessSelectionNeedsAutoAdvanceAndRejectsUnknownCode() {
        begin(); businessOptions();
        when(classifier.classify(anyLong(), anyString(), anyString(), anyString(), anyList()))
            .thenReturn(new AiDialogueUnderstandingService.Decision("SELECT", .99, "自己", "SELF"));
        assertNull(run("给自己申请").branchValue());
        assertEquals("AUTO_ADVANCE_DISABLED", variables.get("workflow.dialogue.q.reason"));
        config.put("aiAutoAdvance", true);
        when(classifier.classify(anyLong(), anyString(), anyString(), anyString(), anyList()))
            .thenReturn(new AiDialogueUnderstandingService.Decision("SELECT", .99, "自己", "UNKNOWN"));
        assertNull(run("给自己申请").branchValue());
    }
    @Test void classificationFailureIsVisibleWithoutLeakingProviderError() {
        begin();
        when(classifier.classify(anyLong(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("secret-key"));
        run("给我申请");
        assertEquals(true, variables.get("workflow.dialogue.q.aiCalled"));
        assertEquals("CLASSIFICATION_FAILED", variables.get("workflow.dialogue.q.reason"));
        assertFalse(variables.toString().contains("secret-key"));
    }
    @Test void optionProtocolRejectsInventedOrContradictorySelection() throws Exception {
        String valid = "{\"kind\":\"SELECT\",\"confidence\":0.99,\"evidence\":\"我自己\",\"optionCode\":\"SELF\"}";
        assertEquals("SELF", AiDialogueUnderstandingServiceImpl.parse(valid, "给我自己申请", Set.of("SELF", "FAMILY")).optionCode());
        assertThrows(Exception.class, () -> AiDialogueUnderstandingServiceImpl.parse(valid.replace("SELF", "OTHER"), "给我自己申请", Set.of("SELF")));
        assertThrows(Exception.class, () -> AiDialogueUnderstandingServiceImpl.parse(valid.replace("SELECT", "QUESTION"), "给我自己申请", Set.of("SELF")));
        assertThrows(Exception.class, () -> AiDialogueUnderstandingServiceImpl.parse(valid, "我不是本人", Set.of("SELF")));
    }
    @Test void optionDefinitionsRejectOverlappingExamples() {
        begin(); businessOptions();
        config.withArray("answerOptions").addObject().put("code", "AGENT").put("label", "代理人").put("answers", "我是家属。");
        assertThrows(Exception.class, () -> run("我是家属"));
    }
}
