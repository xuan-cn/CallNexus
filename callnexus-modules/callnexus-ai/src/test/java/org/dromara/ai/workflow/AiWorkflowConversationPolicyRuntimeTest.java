package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.mapper.*;
import org.dromara.ai.service.impl.AiWorkflowRuntimeServiceImpl;
import org.dromara.ai.workflow.handler.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiWorkflowConversationPolicyRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiWorkflowMapper workflows = mock(AiWorkflowMapper.class);
    private final AiWorkflowVersionMapper versions = mock(AiWorkflowVersionMapper.class);
    private final AiWorkflowExecutionMapper executions = mock(AiWorkflowExecutionMapper.class);
    private final AiWorkflowNodeLogMapper logs = mock(AiWorkflowNodeLogMapper.class);
    private final AiWorkflowWaitMapper waits = mock(AiWorkflowWaitMapper.class);
    private final AiAgentWorkflowBindingMapper bindings = mock(AiAgentWorkflowBindingMapper.class);
    private final AiWorkflowVersion version = new AiWorkflowVersion();
    private final List<AiWorkflowWait> waitRecords = new ArrayList<>();
    private AiWorkflowExecution stored;
    private final org.dromara.ai.service.AiAgentApplicationService knowledge = mock(org.dromara.ai.service.AiAgentApplicationService.class);
    private final AiDialogueUnderstandingService publicAi = mock(AiDialogueUnderstandingService.class);
    private final AiWorkflowRuntimeServiceImpl service = new AiWorkflowRuntimeServiceImpl(workflows, versions, executions, logs, waits, bindings,
        new AiWorkflowNodeHandlerRegistry(List.of(new FlowControlNodeHandler(), new TemplateReplyNodeHandler(new AiWorkflowTemplateResolver()), new AiReplyNodeHandler(knowledge, new AiWorkflowTemplateResolver()))), WorkflowRuleTestSupport.matcher(publicAi));
    private ObjectNode definition;

    private void setup(boolean required, boolean interrupt) throws Exception {
        definition = (ObjectNode) json.readTree("""
            {"settings":{},"nodes":[{"id":"start","type":"START"},
            {"id":"notice","type":"TEMPLATE_REPLY","name":"隐私告知","config":{"text":"资料仅用于本次办理。"}},
            {"id":"wait","type":"WAIT_INPUT","config":{}},{"id":"end","type":"END"}],
            "edges":[{"source":"start","target":"notice"},{"source":"notice","target":"wait"},{"source":"wait","target":"end"}]}
            """);
        ((ObjectNode) definition.path("nodes").get(1).path("config")).put("requiredDisclosure", required)
            .put("disclosureBargeInMode", interrupt ? "REPLAY_AFTER_REPLY" : "DISABLED");
        var workflow = new AiWorkflow(); workflow.setId(1L); workflow.setEnabled(true);
        version.setId(11L); version.setWorkflowId(1L); version.setVersionNo(1); version.setStatus("PUBLISHED");
        var binding = new AiAgentWorkflowBinding(); binding.setWorkflowId(1L); binding.setWorkflowVersionId(11L);
        when(workflows.selectById(1L)).thenReturn(workflow);
        when(versions.selectById(11L)).thenAnswer(call -> { version.setDefinitionJson(definition.toString()); return version; });
        when(versions.selectOne(any())).thenAnswer(call -> { version.setDefinitionJson(definition.toString()); return version; });
        when(bindings.selectOne(any())).thenReturn(binding);
        when(executions.insert(any(AiWorkflowExecution.class))).thenAnswer(call -> { stored = call.getArgument(0); stored.setId(1L); return 1; });
        when(executions.selectOne(any())).thenAnswer(call -> stored);
        when(executions.updateById(any(AiWorkflowExecution.class))).thenReturn(1);
        when(waits.insert(any(AiWorkflowWait.class))).thenAnswer(call -> { var wait = (AiWorkflowWait) call.getArgument(0); wait.setId((long) waitRecords.size()+1); waitRecords.add(wait); return 1; });
        when(waits.selectOne(any())).thenAnswer(call -> waitRecords.stream().filter(wait -> "WAITING".equals(wait.getStatus())).reduce((a,b) -> b).orElse(null));
        when(waits.updateById(any(AiWorkflowWait.class))).thenReturn(1);
        when(logs.selectList(any())).thenReturn(List.of());
    }
    private String start() { return service.startVoice(8L, "call", "VOICE_OUTBOUND", Map.of()).orElseThrow().getExecutionId(); }
    private String state() throws Exception { return json.readTree(stored.getContextJson()).path("variables").path("workflow.disclosure.notice").asText(); }
    private void rule(String event, String action, String phrases, int count, String reply) {
        ((ObjectNode) definition.path("settings")).putArray("conversationRules").addObject().put("event", event).put("enabled", true)
            .put("action", action).put("phrases", phrases).put("queueCode", "SERVICE").put("maxCount", count).put("replyText", reply);
    }
    @Test void requiredDisclosureWaitsForCompletionAndRejectsForbiddenInterruption() throws Exception {
        setup(true, false); String id = start();
        assertEquals("PENDING", state());
        service.voiceInput(id, "early", "是的");
        assertEquals("WAITING_TTS", stored.getStatus()); assertEquals("PENDING", state());
        service.voiceTtsCompleted(id);
        assertEquals("WAITING_INPUT", stored.getStatus()); assertEquals("PLAYBACK_COMPLETED", state());
    }
    @Test void interruptionReplaysFullDisclosureAndNeverAdvancesOnTheInterruptedAnswer() throws Exception {
        setup(true, true); String id = start();
        var replay = service.voiceInput(id, "interrupt", "为什么需要资料");
        assertEquals("INTERRUPTED", state()); assertEquals("WAITING_TTS", stored.getStatus());
        assertTrue(replay.getText().endsWith("资料仅用于本次办理。"));
        int count = waitRecords.size(); service.voiceInput(id, "interrupt", "为什么需要资料");
        assertEquals(count, waitRecords.size());
        service.voiceTtsCompleted(id); assertEquals("PLAYBACK_COMPLETED", state());
        assertEquals("WAITING_INPUT", stored.getStatus());
    }
    @Test void endingCallAbortsDisclosureAndLateCallbackCannotReviveIt() throws Exception {
        setup(true, true); String id = start(); service.terminate(id, "CHANNEL_HANGUP");
        service.voiceTtsCompleted(id); service.voiceInput(id, "late", "转人工");
        assertEquals("TERMINATED", stored.getStatus()); assertEquals("ABORTED", state());
    }
    @Test void testModeRequiresExplicitSimulatedPlaybackCompletion() throws Exception {
        setup(true, false); var request = new AiWorkflowTestStartRequest();
        var initial = service.startTest(1L, request); assertEquals("WAITING_TTS", initial.getStatus());
        var input = new AiWorkflowTestInputRequest(); input.setInputId("ack"); input.setSignal("PLAYBACK_COMPLETED");
        var result = service.input(initial.getExecutionId(), input);
        assertEquals("WAITING_INPUT", result.getStatus()); assertEquals("SIMULATED_COMPLETED", state());
        assertEquals("WAITING_INPUT", service.input(initial.getExecutionId(), input).getStatus());
    }
    @Test void explicitTransferRuleSpeaksThenTransfersWithoutCompletingDisclosure() throws Exception {
        setup(true, true); rule("TRANSFER", "TRANSFER_QUEUE", "转人工", 1, "现在为您转人工"); String id = start();
        assertEquals("SPEAK", service.voiceInput(id, "transfer", "转人工。").getActionType());
        assertEquals("CANCELLED_BY_RULE", state());
        var action = service.voiceTtsCompleted(id);
        assertEquals("TRANSFER_QUEUE", action.getActionType()); assertEquals("SERVICE", action.getTarget());
        assertEquals("TRANSFERRED", stored.getStatus());
    }
    @Test void semanticRuleUsesConfiguredQueueAndLateInputCannotReviveEndedExecution() throws Exception {
        setup(false, false); rule("TRANSFER", "TRANSFER_QUEUE", "转人工", 1, "");
        ((ObjectNode) definition.path("settings").path("conversationRules").get(0)).put("aiUnderstandingEnabled", true);
        when(publicAi.classifyRules(anyLong(), anyString(), anyString(), anyList())).thenReturn(new AiDialogueUnderstandingService.Decision("SELECT", .96, "找个真人", "TRANSFER"));
        String id = start(); service.voiceTtsCompleted(id);
        var action = service.voiceInput(id, "semantic", "找个真人");
        assertEquals("TRANSFER_QUEUE", action.getActionType()); assertEquals("SERVICE", action.getTarget());
        service.terminate(id, "CHANNEL_HANGUP");
        assertEquals("TRANSFERRED", service.voiceInput(id, "late", "找个真人").getStatus());
        verify(publicAi, times(1)).classifyRules(anyLong(), anyString(), anyString(), anyList());
    }
    @Test void negativeTransferPhraseNeverMatchesAndNodeCanDisableRules() throws Exception {
        setup(false, false); rule("TRANSFER", "TRANSFER_QUEUE", "转人工", 1, ""); String id = start(); service.voiceTtsCompleted(id);
        assertEquals("COMPLETED", service.voiceInput(id, "negative", "不要转人工").getStatus());
        ((ObjectNode) definition.path("nodes").get(2).path("config")).put("conversationRuleMode", "DISABLED");
        id = start(); service.voiceTtsCompleted(id);
        assertEquals("COMPLETED", service.voiceInput(id, "disabled", "转人工").getStatus());
    }
    @Test void silenceHasSeparateThresholdAndDuplicateEventIsIgnored() throws Exception {
        setup(false, false); rule("SILENCE", "HANGUP", "", 2, "再见"); String id = start(); service.voiceTtsCompleted(id);
        assertEquals("SPEAK", service.voiceSignal(id, "silence1", "SILENCE").getActionType());
        int waits = waitRecords.size(); service.voiceSignal(id, "silence1", "SILENCE"); assertEquals(waits, waitRecords.size());
        service.voiceTtsCompleted(id);
        assertEquals("SPEAK", service.voiceSignal(id, "silence2", "SILENCE").getActionType());
        assertEquals("HANGUP", service.voiceTtsCompleted(id).getActionType());
    }
    @Test void missingSilenceRuleLeavesLegacyHandlerInCharge() throws Exception {
        setup(false, false); String id = start(); service.voiceTtsCompleted(id);
        assertNull(service.voiceSignal(id, "silence", "SILENCE"));
        assertEquals("WAITING_INPUT", stored.getStatus());
    }
    @Test void ruleValidationRejectsDuplicateOrUnsafeConfiguration() throws Exception {
        assertThrows(Exception.class, () -> AiWorkflowConversationRules.validate(json.readTree("[{\"event\":\"TRANSFER\",\"enabled\":true,\"phrases\":\"转人工\",\"action\":\"TRANSFER_QUEUE\"}]")));
        assertThrows(Exception.class, () -> AiWorkflowConversationRules.validate(json.readTree("[{\"event\":\"DECLINE\",\"enabled\":true,\"phrases\":\"不了\"},{\"event\":\"LATER\",\"enabled\":true,\"phrases\":\"不了。\"}]")));
    }
    @Test void acknowledgementSkipsRetrievalButRealQuestionUsesKnowledgeOnly() throws Exception {
        setup(true, true); String id = start();
        var reply = service.voiceInput(id, "greeting", "嗯，你好。");
        assertTrue(reply.getText().startsWith("好的，我先把这段重要说明补充完整。"));
        verifyNoInteractions(knowledge);
        when(knowledge.chatOnceKnowledgeOnly(8L, null, "你好，怎么上传材料？"))
            .thenReturn(new org.dromara.ai.domain.response.AiChatTurnResult(99L, "按短信指引上传。", "FAQ_EXACT", Map.of("hit", true, "fallback", false)));
        var answer = service.voiceInput(id, "question", "你好，怎么上传材料？");
        assertTrue(answer.getText().startsWith("按短信指引上传。"));
        assertTrue(answer.getText().endsWith("资料仅用于本次办理。"));
        verify(knowledge).chatOnceKnowledgeOnly(8L, null, "你好，怎么上传材料？");
        verify(knowledge, never()).chatOnce(any(), any(), any());
        verify(knowledge, never()).chatOnceModel(any(), any(), any());
    }
    @Test void disclosureAnswerModeOffSkipsQuestionsButStillReplaysNotice() throws Exception {
        setup(true, true);
        ((ObjectNode) definition.path("nodes").get(1).path("config")).put("answerMode", "OFF");
        String id = start();
        var reply = service.voiceInput(id, "question", "怎么上传材料？");
        assertTrue(reply.getText().endsWith("资料仅用于本次办理。")); verifyNoInteractions(knowledge);
        assertEquals("WAITING_TTS", stored.getStatus());
    }
    @Test void disclosureModelFallbackIsExplicitAndStillRequiresFullReplay() throws Exception {
        setup(true, true);
        ((ObjectNode) definition.path("nodes").get(1).path("config")).put("answerMode", "MODEL_FALLBACK");
        when(knowledge.chatOnce(8L, null, "怎么操作？")).thenReturn(new org.dromara.ai.domain.response.AiChatTurnResult(99L, "模型说明。", "MODEL", Map.of("hit", false, "fallback", true)));
        String id = start();
        var reply = service.voiceInput(id, "question", "怎么操作？");
        assertTrue(reply.getText().startsWith("模型说明。")); assertTrue(reply.getText().endsWith("资料仅用于本次办理。"));
        assertEquals("INTERRUPTED", state());
        verify(knowledge, never()).chatOnceKnowledgeOnly(any(), any(), any());
    }
}
