package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.AiWorkflowExecution;
import org.dromara.ai.domain.AiWorkflowNodeLog;
import org.dromara.ai.mapper.*;
import org.dromara.ai.service.impl.AiWorkflowRuntimeServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiWorkflowDialogueTraceTest {
    private final ObjectMapper json = new ObjectMapper();
    private AiWorkflowNodeLog record(Map<String, Object> updates) throws Exception {
        var logs = mock(AiWorkflowNodeLogMapper.class);
        var service = new AiWorkflowRuntimeServiceImpl(mock(AiWorkflowMapper.class), mock(AiWorkflowVersionMapper.class),
            mock(AiWorkflowExecutionMapper.class), logs, mock(AiWorkflowWaitMapper.class), mock(AiAgentWorkflowBindingMapper.class), mock(AiWorkflowNodeHandlerRegistry.class), WorkflowRuleTestSupport.matcher());
        var node = json.readTree("""
            {"id":"q","type":"DIALOGUE_STEP","config":{"answerOptions":[{"code":"FAMILY","label":"家属"},{"code":"SELF","label":"本人"}]}}
            """);
        var result = new AiWorkflowNodeResult("CONTINUE", "OPTION_FAMILY", "对话步骤结果：家属", null, updates);
        var method = AiWorkflowRuntimeServiceImpl.class.getDeclaredMethod("logNode", AiWorkflowExecution.class, JsonNode.class, AiWorkflowNodeResult.class, String.class, LocalDateTime.class, Exception.class);
        method.setAccessible(true);
        method.invoke(service, new AiWorkflowExecution(), node, result, "我是他爸爸", LocalDateTime.now(), null);
        var capture = ArgumentCaptor.forClass(AiWorkflowNodeLog.class);
        verify(logs).insert(capture.capture());
        return capture.getValue();
    }
    @Test void savesReadableLabelsConflictCandidatesAndMeasuredTimings() throws Exception {
        var log = record(Map.of("workflow.dialogue.q.optionCode", "FAMILY", "workflow.dialogue.q.matchedBranches", List.of("OPTION_SELF", "OPTION_FAMILY"),
            "workflow.dialogue.q.matchReason", "MATCH_CONFLICT", "workflow.dialogue.q.timings", Map.of("matching", 0L, "understanding", 12L)));
        var details = json.readTree(log.getOutputSummary().substring("DIALOGUE_TRACE:".length())).path("details");
        assertEquals("家属", details.path("branchLabel").asText());
        assertEquals("家属", details.path("optionLabel").asText());
        assertEquals("本人", details.path("matchedLabels").get(0).asText());
        assertEquals(12, details.path("timings").path("understanding").asInt());
        assertEquals("MATCH_CONFLICT", details.path("matchReason").asText());
    }
    @Test void oversizedDetailsAreExplicitlyMarkedWithoutLosingDecision() throws Exception {
        var log = record(Map.of("workflow.dialogue.q.reason", "MISSING_FIELDS", "workflow.dialogue.q.missingFields", List.of("医院".repeat(1500)), "workflow.dialogue.q.evidence", "原文".repeat(300)));
        assertTrue(log.getOutputSummary().length() <= 2000);
        var details = json.readTree(log.getOutputSummary().substring("DIALOGUE_TRACE:".length())).path("details");
        assertTrue(details.path("detailsTruncated").asBoolean());
        assertEquals("MISSING_FIELDS", details.path("reason").asText());
        assertEquals("家属", details.path("branchLabel").asText());
    }
}
