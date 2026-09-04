package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiAgentRequest;
import org.dromara.ai.domain.request.AiChatRequest;
import org.dromara.ai.domain.response.*;
import java.util.List;
import java.util.function.BiConsumer;

public interface AiAgentApplicationService {
    List<AiAgentResponse> agents();
    AiAgentResponse agent(Long id);
    Long createAgent(AiAgentRequest request);
    void updateAgent(Long id, AiAgentRequest request);
    void deleteAgent(Long id);
    void setAgentEnabled(Long id, boolean enabled);
    AiConversationStartResponse startConversation(Long agentId, Long userId);
    List<AiConversationResponse> conversations(Long agentId);
    List<AiMessageResponse> messages(Long conversationId);
    void deleteConversation(Long conversationId);
    void deleteConversations(Long agentId);
    void streamChat(Long agentId, Long userId, AiChatRequest request, BiConsumer<String, Object> eventConsumer);
    AiConversationStartResponse startRealtimeConversation(Long agentId);
    AiChatTurnResult chatOnce(Long agentId, Long conversationId, String message);
    AiChatTurnResult chatOnceModel(Long agentId, Long conversationId, String message);
}
