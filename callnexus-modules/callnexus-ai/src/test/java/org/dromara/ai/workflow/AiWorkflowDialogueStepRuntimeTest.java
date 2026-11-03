package org.dromara.ai.workflow;

import org.dromara.ai.domain.*;
import org.dromara.ai.mapper.*;
import org.dromara.ai.service.impl.AiWorkflowRuntimeServiceImpl;
import org.dromara.ai.workflow.handler.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class AiWorkflowDialogueStepRuntimeTest {
    @Test void voiceQuestionAndClarificationStayAtSameStepUntilExplicitAnswer() {
        var workflows = mock(AiWorkflowMapper.class);
        var versions = mock(AiWorkflowVersionMapper.class);
        var executions = mock(AiWorkflowExecutionMapper.class);
        var logs = mock(AiWorkflowNodeLogMapper.class);
        var waits = mock(AiWorkflowWaitMapper.class);
        List<AiWorkflowNodeLog> logRecords = new ArrayList<>();
        when(logs.insert(any(AiWorkflowNodeLog.class))).thenAnswer(call -> { logRecords.add(call.getArgument(0)); return 1; });
        when(logs.selectList(any())).thenAnswer(call -> logRecords);
        var bindings = mock(AiAgentWorkflowBindingMapper.class);
        var workflow = new AiWorkflow(); workflow.setId(1L); workflow.setEnabled(true);
        var version = new AiWorkflowVersion(); version.setId(11L); version.setWorkflowId(1L); version.setVersionNo(1);
        version.setStatus("PUBLISHED");
        version.setDefinitionJson("""
            {"schemaVersion":"1.0","variables":[],"nodes":[
              {"id":"start","type":"START"},
              {"id":"q","type":"DIALOGUE_STEP","config":{"text":"需要办理吗？","acceptedAnswers":"需要","declinedAnswers":"不用","retryText":"请确认是否需要办理","maxRetries":2}},
              {"id":"end","type":"END"}],"edges":[{"source":"start","target":"q"},
              {"source":"q","target":"end","condition":"COMPLETE"},{"source":"q","target":"end","condition":"DECLINED"},{"source":"q","target":"end","condition":"LIMIT"}]}
            """);
        var binding = new AiAgentWorkflowBinding(); binding.setWorkflowId(1L); binding.setWorkflowVersionId(11L);
        when(workflows.selectById(1L)).thenReturn(workflow);
        when(versions.selectById(11L)).thenReturn(version);
        when(bindings.selectOne(any())).thenReturn(binding);
        var stored = new AiWorkflowExecution[1];
        when(executions.insert(any(AiWorkflowExecution.class))).thenAnswer(call -> { stored[0] = call.getArgument(0); stored[0].setId(10L); return 1; });
        when(executions.selectOne(any())).thenAnswer(call -> stored[0]);
        when(executions.updateById(any(AiWorkflowExecution.class))).thenReturn(1);
        List<AiWorkflowWait> records = new ArrayList<>();
        when(waits.insert(any(AiWorkflowWait.class))).thenAnswer(call -> { AiWorkflowWait value = call.getArgument(0); value.setId((long) records.size() + 1); records.add(value); return 1; });
        when(waits.selectOne(any())).thenAnswer(call -> records.stream().filter(value -> "WAITING".equals(value.getStatus())).reduce((a,b) -> b).orElse(null));
        when(waits.updateById(any(AiWorkflowWait.class))).thenReturn(1);
        var handlers = new AiWorkflowNodeHandlerRegistry(List.of(new FlowControlNodeHandler(), new DialogueStepNodeHandler(new AiWorkflowTemplateResolver(),
            mock(AiDialogueUnderstandingService.class), mock(org.dromara.ai.service.AiAgentApplicationService.class), mock(AiWorkflowSlotExtractionService.class), new DialogueAnswerMatcher(mock(org.dromara.ai.service.AiIntentApplicationService.class)))));
        var service = new AiWorkflowRuntimeServiceImpl(workflows, versions, executions, logs, waits, bindings, handlers, WorkflowRuleTestSupport.matcher());
        var result = service.startVoice(8L, "call", "VOICE_INBOUND", Map.of()).orElseThrow();
        assertEquals("SPEAK", result.getActionType());
        assertEquals("WAITING_TTS", result.getStatus());
        String id = result.getExecutionId();
        assertEquals("WAIT_INPUT", service.voiceTtsCompleted(id).getActionType());
        assertEquals("SPEAK", service.voiceInput(id, "input1", "还没确定").getActionType());
        assertEquals("q", stored[0].getCurrentNodeId());
        service.voiceTtsCompleted(id);
        service.voiceInput(id, "input2", "需要");
        assertEquals("COMPLETED", stored[0].getStatus());
        var traces = service.execution(id).getTraces().stream().filter(trace -> "DIALOGUE_STEP".equals(trace.getNodeType())).toList();
        assertEquals(3, traces.size());
        assertEquals("ASKING", traces.get(0).getDialogueDetails().get("reason"));
        assertEquals("AI_DISABLED", traces.get(1).getDialogueDetails().get("reason"));
        assertEquals("EXACT_MATCH", traces.get(2).getDialogueDetails().get("reason"));
        assertEquals(false, traces.get(2).getDialogueDetails().get("aiCalled"));
        assertEquals("确认完成", traces.get(2).getDialogueDetails().get("branchLabel"));
        assertEquals(List.of("确认完成"), traces.get(2).getDialogueDetails().get("matchedLabels"));
        assertTrue(((Map<?, ?>) traces.get(2).getDialogueDetails().get("timings")).containsKey("matching"));
        assertFalse(traces.get(0).getOutputSummary().startsWith("DIALOGUE_TRACE:"));
    }
}
