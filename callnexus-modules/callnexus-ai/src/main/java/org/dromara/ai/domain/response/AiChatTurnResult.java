package org.dromara.ai.domain.response;

public record AiChatTurnResult(
    Long conversationId,
    String answer,
    String sourceType
) {
}
