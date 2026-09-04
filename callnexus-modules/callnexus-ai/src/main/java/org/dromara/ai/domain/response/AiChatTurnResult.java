package org.dromara.ai.domain.response;

import java.util.Map;

public record AiChatTurnResult(
    Long conversationId,
    String answer,
    String sourceType,
    Map<String, Object> retrieval
) {
    public AiChatTurnResult(Long conversationId, String answer, String sourceType) {
        this(conversationId, answer, sourceType, Map.of());
    }
}
