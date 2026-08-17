package org.dromara.chat.domain.response;

import java.time.LocalDateTime;
import java.util.List;

public final class ChatResponses {
    private ChatResponses() {
    }

    public record Channel(
        Long id,
        String channelKey,
        String channelName,
        Long skillGroupId,
        Boolean aiEnabled,
        Long aiAgentId,
        String welcomeMessage,
        String offlineMessage,
        String allowedOrigins,
        Boolean enabled,
        Integer version
    ) {
    }

    public record Bootstrap(String channelKey, String channelName, String welcomeMessage, String offlineMessage) {
    }

    public record ConversationCreated(Long conversationId, String conversationNo, String visitorToken, String status) {
    }

    public record Conversation(
        Long id,
        String conversationNo,
        Long channelId,
        String channelName,
        Long skillGroupId,
        Long aiAgentId,
        Long visitorId,
        String visitorName,
        String phone,
        String email,
        String status,
        Integer priority,
        Long assignedUserId,
        String assignedUserName,
        Long customerId,
        Long ticketId,
        LocalDateTime queuedAt,
        LocalDateTime assignedAt,
        LocalDateTime closedAt,
        LocalDateTime lastMessageAt,
        Integer unreadAgentCount,
        Integer unreadVisitorCount
    ) {
    }

    public record Message(
        Long id,
        Long conversationId,
        String senderType,
        Long senderId,
        String senderName,
        String messageType,
        String content,
        LocalDateTime sentAt
    ) {
    }

    public record ConversationDetail(Conversation conversation, List<Message> messages) {
    }
}
