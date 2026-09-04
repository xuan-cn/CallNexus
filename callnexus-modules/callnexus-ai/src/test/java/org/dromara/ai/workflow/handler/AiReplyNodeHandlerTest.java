package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowTemplateResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AiReplyNodeHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldUseCurrentInputForKnowledgeQueryAndPersistConversation() throws Exception {
        AiAgentApplicationService agentService = mock(AiAgentApplicationService.class);
        AiWorkflowTemplateResolver resolver = mock(AiWorkflowTemplateResolver.class);
        when(resolver.resolve("{{conversation.currentInput}}", Map.of("conversation.currentInput", "怎么购买")))
            .thenReturn("怎么购买");
        when(agentService.chatOnce(8L, null, "怎么购买"))
            .thenReturn(new AiChatTurnResult(99L, "可以在线购买。", "FAQ_EXACT", Map.of(
                "hit", true, "score", 1D, "threshold", 0.8D, "hitCount", 1,
                "fallback", false, "reason", "HIT")));
        AiReplyNodeHandler handler = new AiReplyNodeHandler(agentService, resolver);

        var result = handler.execute(new AiWorkflowNodeContext(
            OBJECT_MAPPER.readTree("{\"type\":\"KNOWLEDGE_QUERY\",\"config\":{\"queryTemplate\":\"{{conversation.currentInput}}\"}}"),
            Map.of("conversation.currentInput", "怎么购买"), "怎么购买", 8L));

        assertThat(result.status()).isEqualTo("SPEAK");
        assertThat(result.output()).isEqualTo("可以在线购买。");
        assertThat(result.variableUpdates()).containsEntry("ai.conversationId", 99L)
            .containsEntry("ai.answerSource", "FAQ_EXACT")
            .containsEntry("knowledge.hit", true)
            .containsEntry("knowledge.score", 1D)
            .containsEntry("knowledge.threshold", 0.8D)
            .containsEntry("knowledge.reason", "HIT");
        verify(agentService).chatOnce(8L, null, "怎么购买");
    }

    @Test
    void shouldReuseConversationForModelReply() throws Exception {
        AiAgentApplicationService agentService = mock(AiAgentApplicationService.class);
        AiWorkflowTemplateResolver resolver = mock(AiWorkflowTemplateResolver.class);
        Map<String, Object> variables = Map.of("ai.conversationId", 99L, "conversation.currentInput", "继续");
        when(resolver.resolve("", variables)).thenReturn("");
        when(agentService.chatOnceModel(8L, 99L, "继续"))
            .thenReturn(new AiChatTurnResult(99L, "好的。", "MODEL"));
        AiReplyNodeHandler handler = new AiReplyNodeHandler(agentService, resolver);

        var result = handler.execute(new AiWorkflowNodeContext(
            OBJECT_MAPPER.readTree("{\"type\":\"MODEL_REPLY\",\"config\":{}}"), variables, "继续", 8L));

        assertThat(result.output()).isEqualTo("好的。");
        verify(agentService).chatOnceModel(8L, 99L, "继续");
    }

    @Test
    void shouldExposeKnowledgeFallbackDetails() throws Exception {
        AiAgentApplicationService agentService = mock(AiAgentApplicationService.class);
        AiWorkflowTemplateResolver resolver = mock(AiWorkflowTemplateResolver.class);
        Map<String, Object> variables = Map.of("conversation.currentInput", "未知问题");
        when(resolver.resolve("{{conversation.currentInput}}", variables)).thenReturn("未知问题");
        when(agentService.chatOnce(8L, null, "未知问题"))
            .thenReturn(new AiChatTurnResult(100L, "模型回答", "MODEL", Map.of(
                "hit", false, "score", 0.42D, "threshold", 0.7D, "hitCount", 0,
                "fallback", true, "reason", "BELOW_THRESHOLD")));
        AiReplyNodeHandler handler = new AiReplyNodeHandler(agentService, resolver);

        var result = handler.execute(new AiWorkflowNodeContext(
            OBJECT_MAPPER.readTree("{\"type\":\"KNOWLEDGE_QUERY\",\"config\":{\"queryTemplate\":\"{{conversation.currentInput}}\"}}"),
            variables, "未知问题", 8L));

        assertThat(result.variableUpdates()).containsEntry("knowledge.hit", false)
            .containsEntry("knowledge.fallback", true)
            .containsEntry("knowledge.reason", "BELOW_THRESHOLD")
            .containsEntry("knowledge.source", "MODEL");
    }
}
