package org.dromara.chat.service;

import org.dromara.chat.domain.request.ChatConversationQuery;
import org.dromara.chat.domain.request.ChatRequests;
import org.dromara.chat.domain.response.ChatResponses;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;

import java.util.List;

public interface ChatConversationService {
    ChatResponses.Bootstrap bootstrap(String channelKey, String origin);

    ChatResponses.ConversationCreated createPublicConversation(
        String channelKey,
        String origin,
        ChatRequests.CreateConversation request
    );

    List<ChatResponses.Message> listPublicMessages(Long conversationId, String visitorToken, Long afterId);

    ChatResponses.Message sendPublicMessage(Long conversationId, String visitorToken, ChatRequests.SendMessage request);

    TableDataInfo<ChatResponses.Conversation> page(ChatConversationQuery query, PageQuery pageQuery);

    ChatResponses.ConversationDetail detail(Long conversationId);

    void claim(Long conversationId);

    ChatResponses.Message sendAgentMessage(Long conversationId, ChatRequests.SendMessage request);

    void close(Long conversationId);

    void markAgentRead(Long conversationId);
}
