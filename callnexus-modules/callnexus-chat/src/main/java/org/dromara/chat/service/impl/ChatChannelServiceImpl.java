package org.dromara.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.chat.domain.ChatChannel;
import org.dromara.chat.domain.ChatConversation;
import org.dromara.chat.domain.request.ChatRequests;
import org.dromara.chat.domain.response.ChatResponses;
import org.dromara.chat.mapper.ChatChannelMapper;
import org.dromara.chat.mapper.ChatConversationMapper;
import org.dromara.chat.service.ChatChannelService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class ChatChannelServiceImpl implements ChatChannelService {
    private final ChatChannelMapper channelMapper;
    private final ChatConversationMapper conversationMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public TableDataInfo<ChatResponses.Channel> page(String channelName, Boolean enabled, PageQuery pageQuery) {
        Page<ChatChannel> page = channelMapper.selectPage(pageQuery.build(), new LambdaQueryWrapper<ChatChannel>()
            .like(hasText(channelName), ChatChannel::getChannelName, channelName)
            .eq(enabled != null, ChatChannel::getEnabled, enabled)
            .orderByDesc(ChatChannel::getCreateTime));
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public ChatResponses.Channel get(Long id) {
        return toResponse(requireChannel(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ChatRequests.SaveChannel request) {
        ChatChannel channel = new ChatChannel();
        channel.setChannelKey(randomChannelKey());
        apply(channel, request);
        channelMapper.insert(channel);
        return channel.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ChatRequests.SaveChannel request) {
        ChatChannel channel = requireChannel(id);
        if (request.getVersion() != null) {
            channel.setVersion(request.getVersion());
        }
        apply(channel, request);
        if (channelMapper.updateById(channel) == 0) {
            throw new ServiceException("渠道已被其他用户修改，请刷新后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireChannel(id);
        long activeCount = conversationMapper.selectCount(new LambdaQueryWrapper<ChatConversation>()
            .eq(ChatConversation::getChannelId, id)
            .in(ChatConversation::getStatus, "AI_SERVING", "QUEUING", "ACTIVE"));
        if (activeCount > 0) {
            throw new ServiceException("渠道存在排队中或服务中的会话，不能删除");
        }
        channelMapper.deleteById(id);
    }

    private ChatChannel requireChannel(Long id) {
        ChatChannel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new ServiceException("在线客服渠道不存在");
        }
        return channel;
    }

    private void apply(ChatChannel channel, ChatRequests.SaveChannel request) {
        channel.setChannelName(request.getChannelName().trim());
        channel.setSkillGroupId(request.getSkillGroupId());
        channel.setAiEnabled(Boolean.TRUE.equals(request.getAiEnabled()));
        channel.setAiAgentId(Boolean.TRUE.equals(request.getAiEnabled()) ? request.getAiAgentId() : null);
        if (Boolean.TRUE.equals(channel.getAiEnabled()) && channel.getAiAgentId() == null) {
            throw new ServiceException("启用 AI 接待时必须选择 AI 助手");
        }
        channel.setWelcomeMessage(trimToNull(request.getWelcomeMessage()));
        channel.setOfflineMessage(trimToNull(request.getOfflineMessage()));
        channel.setAllowedOrigins(trimToNull(request.getAllowedOrigins()));
        channel.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private String randomChannelKey() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return "chn_" + HexFormat.of().formatHex(bytes);
    }

    private ChatResponses.Channel toResponse(ChatChannel channel) {
        return new ChatResponses.Channel(
            channel.getId(),
            channel.getChannelKey(),
            channel.getChannelName(),
            channel.getSkillGroupId(),
            Boolean.TRUE.equals(channel.getAiEnabled()),
            channel.getAiAgentId(),
            channel.getWelcomeMessage(),
            channel.getOfflineMessage(),
            channel.getAllowedOrigins(),
            channel.getEnabled(),
            channel.getVersion()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
