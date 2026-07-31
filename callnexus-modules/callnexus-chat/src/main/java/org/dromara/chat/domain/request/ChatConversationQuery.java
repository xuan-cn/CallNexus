package org.dromara.chat.domain.request;

import lombok.Data;

@Data
public class ChatConversationQuery {
    private Long channelId;
    private String status;
    private Boolean assignedToMe;
    private String keyword;
}
