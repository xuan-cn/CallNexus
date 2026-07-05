package org.dromara.ai.domain.response;

import lombok.Data;

@Data
public class AiConversationStartResponse {
    private AiConversationResponse conversation;
    private AiMessageResponse message;
}
