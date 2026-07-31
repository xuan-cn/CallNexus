package org.dromara.chat.service;

import org.dromara.chat.domain.request.ChatRequests;
import org.dromara.chat.domain.response.ChatResponses;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;

public interface ChatChannelService {
    TableDataInfo<ChatResponses.Channel> page(String channelName, Boolean enabled, PageQuery pageQuery);

    ChatResponses.Channel get(Long id);

    Long create(ChatRequests.SaveChannel request);

    void update(Long id, ChatRequests.SaveChannel request);

    void delete(Long id);
}
