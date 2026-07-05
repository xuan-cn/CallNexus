package org.dromara.ai.domain.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiConversationResponse {
    private Long id;
    private Long agentId;
    private String agentName;
    private String title;
    private String status;
    private LocalDateTime lastMessageAt;
}
