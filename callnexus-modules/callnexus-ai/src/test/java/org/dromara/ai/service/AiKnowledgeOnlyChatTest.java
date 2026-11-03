package org.dromara.ai.service;

import org.dromara.ai.domain.*;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.vector.VectorStore;
import org.dromara.ai.service.impl.AiAgentApplicationServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class AiKnowledgeOnlyChatTest {
    private final AiAgentMapper agents = mock(AiAgentMapper.class);
    private final AiAgentKnowledgeBaseMapper bindings = mock(AiAgentKnowledgeBaseMapper.class);
    private final AiKnowledgeBaseMapper bases = mock(AiKnowledgeBaseMapper.class);
    private final AiKnowledgeFaqMapper faqs = mock(AiKnowledgeFaqMapper.class);
    private final AiKnowledgeFaqVersionMapper versions = mock(AiKnowledgeFaqVersionMapper.class);
    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final ChatProviderRegistry chat = mock(ChatProviderRegistry.class);
    private final AiModelMapper models = mock(AiModelMapper.class);
    private final AiFaqLearningApplicationService learning = mock(AiFaqLearningApplicationService.class);
    private final AiAgentApplicationServiceImpl service = new AiAgentApplicationServiceImpl(agents, bindings, bases, faqs, versions,
        mock(AiKnowledgeFaqAliasMapper.class), models, mock(AiModelProviderMapper.class), conversations, messages,
        mock(AiMessageCitationMapper.class), mock(AiModelUsageMapper.class), mock(EmbeddingProviderRegistry.class), chat, mock(VectorStore.class), learning);
    private AiAgent setup(String policy) {
        var agent = new AiAgent(); agent.setId(8L); agent.setEnabled(true); agent.setRetrievalFailurePolicy(policy);
        agent.setFaqScoreThreshold(new BigDecimal("0.8")); agent.setScoreThreshold(new BigDecimal("0.8"));
        when(agents.selectById(8L)).thenReturn(agent);
        when(conversations.insert(any(AiConversation.class))).thenAnswer(call -> { ((AiConversation) call.getArgument(0)).setId(99L); return 1; });
        when(messages.insert(any(AiMessage.class))).thenAnswer(call -> { ((AiMessage) call.getArgument(0)).setId(100L); return 1; });
        return agent;
    }
    @Test void noHitDoesNotCallChatEvenWhenAssistantAllowsModelFallback() {
        setup("FALLBACK_MODEL");
        var result = service.chatOnceKnowledgeOnly(8L, null, "未知问题");
        assertEquals("STRICT", result.sourceType()); assertTrue(result.answer().isEmpty());
        assertEquals(false, result.retrieval().get("hit"));
        assertEquals(false, result.retrieval().get("fallback"));
        assertEquals(true, result.retrieval().get("modelFallbackSkipped"));
        verifyNoInteractions(chat, models, learning);
    }
    @Test void strictKnowledgeMissAlsoReturnsWithoutModelGeneration() {
        setup("STRICT");
        assertEquals("STRICT", service.chatOnceKnowledgeOnly(8L, null, "未知问题").sourceType());
        verifyNoInteractions(chat, models, learning);
    }
    @Test void directFaqHitStillAnswersWithoutChat() {
        setup("FALLBACK_MODEL");
        var binding = new AiAgentKnowledgeBase(); binding.setKnowledgeBaseId(1L);
        var base = new AiKnowledgeBase(); base.setId(1L); base.setEnabled(true); base.setCollectionName("test"); base.setStatus("READY");
        var faq = new AiKnowledgeFaq(); faq.setId(2L); faq.setCurrentVersionId(3L); faq.setAnswerMode("DIRECT"); faq.setFaqName("上传指引");
        var version = new AiKnowledgeFaqVersion(); version.setId(3L); version.setNormalizedQuestion("怎么上传"); version.setStandardAnswer("按短信上传。");
        when(bindings.selectList(any())).thenReturn(List.of(binding)); when(bases.selectById(1L)).thenReturn(base);
        when(faqs.selectList(any())).thenReturn(List.of(faq)); when(versions.selectBatchIds(any())).thenReturn(List.of(version));
        var answer = service.chatOnceKnowledgeOnly(8L, null, "怎么上传");
        assertEquals("FAQ_EXACT", answer.sourceType()); assertEquals("按短信上传。", answer.answer());
        verifyNoInteractions(chat, models, learning);
    }
}
