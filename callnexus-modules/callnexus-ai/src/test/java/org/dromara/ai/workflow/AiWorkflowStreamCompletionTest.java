package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.AiWorkflowExecution;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.mapper.AiAgentWorkflowBindingMapper;
import org.dromara.ai.mapper.AiWorkflowExecutionMapper;
import org.dromara.ai.mapper.AiWorkflowMapper;
import org.dromara.ai.mapper.AiWorkflowNodeLogMapper;
import org.dromara.ai.mapper.AiWorkflowVersionMapper;
import org.dromara.ai.mapper.AiWorkflowWaitMapper;
import org.dromara.ai.service.impl.AiWorkflowRuntimeServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class AiWorkflowStreamCompletionTest {

    @Test
    void shouldPersistStreamAnswerAndKnowledgeMetadataWithoutAdvancingWorkflow() throws Exception {
        AiWorkflowExecutionMapper executionMapper = mock(AiWorkflowExecutionMapper.class);
        AiWorkflowExecution execution = new AiWorkflowExecution();
        execution.setExecutionId("voice-stream-1");
        execution.setStatus("WAITING_TTS");
        execution.setContextJson("""
            {
              "variables": {},
              "outputMessages": [],
              "pendingActionType": "STREAM_AI",
              "pendingActionText": "怎么购买",
              "pendingActionTarget": "KNOWLEDGE_QUERY"
            }
            """);
        when(executionMapper.selectOne(any())).thenReturn(execution);
        when(executionMapper.updateById(any(AiWorkflowExecution.class))).thenReturn(1);

        AiWorkflowRuntimeServiceImpl service = new AiWorkflowRuntimeServiceImpl(
            mock(AiWorkflowMapper.class), mock(AiWorkflowVersionMapper.class), executionMapper,
            mock(AiWorkflowNodeLogMapper.class), mock(AiWorkflowWaitMapper.class),
            mock(AiAgentWorkflowBindingMapper.class), new AiWorkflowNodeHandlerRegistry(List.of()));

        service.voiceStreamCompleted("voice-stream-1", new AiChatTurnResult(
            99L, "可以在线购买。", "MODEL", Map.of(
                "hit", false,
                "hitCount", 0,
                "fallback", true,
                "reason", "BELOW_THRESHOLD"
            )));

        JsonNode context = new ObjectMapper().readTree(execution.getContextJson());
        assertThat(execution.getStatus()).isEqualTo("WAITING_TTS");
        assertThat(context.path("outputMessages").get(0).asText()).isEqualTo("可以在线购买。");
        assertThat(context.path("variables").path("ai.conversationId").asLong()).isEqualTo(99L);
        assertThat(context.path("variables").path("knowledge.fallback").asBoolean()).isTrue();
        assertThat(context.path("variables").path("knowledge.reason").asText()).isEqualTo("BELOW_THRESHOLD");
    }
}
