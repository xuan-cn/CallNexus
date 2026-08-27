package org.dromara.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.chat.domain.ChatChannel;
import org.dromara.chat.domain.ChatConversation;
import org.dromara.chat.domain.ChatMessage;
import org.dromara.chat.domain.ChatVisitor;
import org.dromara.chat.domain.request.ChatConversationQuery;
import org.dromara.chat.domain.request.ChatRequests;
import org.dromara.chat.domain.response.ChatResponses;
import org.dromara.chat.mapper.ChatChannelMapper;
import org.dromara.chat.mapper.ChatConversationMapper;
import org.dromara.chat.mapper.ChatMessageMapper;
import org.dromara.chat.mapper.ChatVisitorMapper;
import org.dromara.chat.service.ChatConversationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatConversationServiceImpl implements ChatConversationService {
    private static final Set<String> OPEN_STATUSES = Set.of("AI_SERVING", "QUEUING", "ACTIVE");
    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int AI_REPLY_LOCK_STRIPES = 64;

    private final ChatChannelMapper channelMapper;
    private final ChatVisitorMapper visitorMapper;
    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AiAgentApplicationService aiAgentApplicationService;
    private final AiIntentApplicationService aiIntentApplicationService;
    @Qualifier("chatAiReplyExecutor")
    private final Executor chatAiReplyExecutor;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Object[] aiReplyLocks = createLocks();

    @Override
    public ChatResponses.Bootstrap bootstrap(String channelKey, String origin) {
        ChatChannel channel = findPublicChannel(channelKey);
        validateOrigin(channel, origin);
        return new ChatResponses.Bootstrap(
            channel.getChannelKey(),
            channel.getChannelName(),
            channel.getWelcomeMessage(),
            channel.getOfflineMessage()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResponses.ConversationCreated createPublicConversation(
        String channelKey,
        String origin,
        ChatRequests.CreateConversation request
    ) {
        ChatChannel channel = findPublicChannel(channelKey);
        validateOrigin(channel, origin);
        return TenantHelper.dynamic(channel.getTenantId(), () -> createConversation(channel, request));
    }

    @Override
    public List<ChatResponses.Message> listPublicMessages(Long conversationId, String visitorToken, Long afterId) {
        ChatConversation publicConversation = findPublicConversation(conversationId);
        validateVisitorToken(publicConversation, visitorToken);
        return TenantHelper.dynamic(publicConversation.getTenantId(), () -> {
            touchVisitor(publicConversation.getVisitorId());
            LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .gt(afterId != null, ChatMessage::getId, afterId)
                .orderByAsc(ChatMessage::getId)
                .last("LIMIT 200");
            List<ChatResponses.Message> messages = messageMapper.selectList(wrapper).stream().map(this::toMessage).toList();
            if (!messages.isEmpty()) {
                conversationMapper.update(null, new LambdaUpdateWrapper<ChatConversation>()
                    .eq(ChatConversation::getId, conversationId)
                    .set(ChatConversation::getUnreadVisitorCount, 0));
            }
            return messages;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResponses.Message sendPublicMessage(
        Long conversationId,
        String visitorToken,
        ChatRequests.SendMessage request
    ) {
        ChatConversation publicConversation = findPublicConversation(conversationId);
        validateVisitorToken(publicConversation, visitorToken);
        return TenantHelper.dynamic(publicConversation.getTenantId(), () -> sendVisitorMessage(publicConversation, request));
    }

    @Override
    public TableDataInfo<ChatResponses.Conversation> page(ChatConversationQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<ChatConversation>()
            .eq(query.getChannelId() != null, ChatConversation::getChannelId, query.getChannelId())
            .eq(Boolean.TRUE.equals(query.getAssignedToMe()), ChatConversation::getAssignedUserId, LoginHelper.getUserId())
            .orderByDesc(ChatConversation::getPriority)
            .orderByDesc(ChatConversation::getLastMessageAt)
            .orderByAsc(ChatConversation::getQueuedAt);
        if ("OPEN".equals(query.getStatus())) {
            wrapper.in(ChatConversation::getStatus, "AI_SERVING", "QUEUING", "ACTIVE");
        } else {
            wrapper.eq(hasText(query.getStatus()), ChatConversation::getStatus, query.getStatus());
        }
        if (hasText(query.getKeyword())) {
            Set<Long> visitorIds = visitorMapper.selectList(new LambdaQueryWrapper<ChatVisitor>()
                    .like(ChatVisitor::getVisitorName, query.getKeyword())
                    .or().like(ChatVisitor::getPhone, query.getKeyword())
                    .or().like(ChatVisitor::getEmail, query.getKeyword()))
                .stream().map(ChatVisitor::getId).collect(Collectors.toSet());
            if (visitorIds.isEmpty()) {
                wrapper.like(ChatConversation::getConversationNo, query.getKeyword());
            } else {
                wrapper.and(condition -> condition.like(ChatConversation::getConversationNo, query.getKeyword())
                    .or().in(ChatConversation::getVisitorId, visitorIds));
            }
        }
        Page<ChatConversation> page = conversationMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toConversation).toList(), page.getTotal());
    }

    @Override
    public ChatResponses.ConversationDetail detail(Long conversationId) {
        ChatConversation conversation = requireConversation(conversationId);
        List<ChatResponses.Message> messages = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getId))
            .stream().map(this::toMessage).toList();
        return new ChatResponses.ConversationDetail(toConversation(conversation), messages);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claim(Long conversationId) {
        ChatConversation conversation = requireConversation(conversationId);
        if ("ACTIVE".equals(conversation.getStatus()) && LoginHelper.getUserId().equals(conversation.getAssignedUserId())) {
            return;
        }
        if (!"QUEUING".equals(conversation.getStatus())) {
            throw new ServiceException("只有排队中的会话可以领取");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<ChatConversation>()
            .eq(ChatConversation::getId, conversationId)
            .eq(ChatConversation::getStatus, "QUEUING")
            .isNull(ChatConversation::getAssignedUserId)
            .set(ChatConversation::getStatus, "ACTIVE")
            .set(ChatConversation::getAssignedUserId, LoginHelper.getUserId())
            .set(ChatConversation::getAssignedUserName, LoginHelper.getUsername())
            .set(ChatConversation::getAssignedAt, now));
        if (updated == 0) {
            throw new ServiceException("会话已被其他坐席领取，请刷新列表");
        }
        insertMessage(conversationId, "SYSTEM", null, "系统", "坐席已接入会话", null, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResponses.Message sendAgentMessage(Long conversationId, ChatRequests.SendMessage request) {
        ChatConversation conversation = requireOwnedActiveConversation(conversationId);
        ChatMessage existing = findDuplicateMessage(conversationId, "AGENT", request.getClientMessageId());
        if (existing != null) {
            return toMessage(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        ChatMessage message = insertMessage(
            conversationId,
            "AGENT",
            LoginHelper.getUserId(),
            LoginHelper.getUsername(),
            request.getContent().trim(),
            trimToNull(request.getClientMessageId()),
            now
        );
        conversationMapper.update(null, new LambdaUpdateWrapper<ChatConversation>()
            .eq(ChatConversation::getId, conversation.getId())
            .set(ChatConversation::getLastMessageAt, now)
            .setSql("unread_visitor_count = unread_visitor_count + 1"));
        return toMessage(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long conversationId) {
        ChatConversation conversation = requireClosableConversation(conversationId);
        LocalDateTime now = LocalDateTime.now();
        conversationMapper.update(null, new LambdaUpdateWrapper<ChatConversation>()
            .eq(ChatConversation::getId, conversation.getId())
            .in(ChatConversation::getStatus, "AI_SERVING", "QUEUING", "ACTIVE")
            .set(ChatConversation::getStatus, "CLOSED")
            .set(ChatConversation::getClosedAt, now));
        insertMessage(conversationId, "SYSTEM", null, "系统", "本次会话已结束", null, now);
    }

    @Override
    public void markAgentRead(Long conversationId) {
        ChatConversation conversation = requireConversation(conversationId);
        if (!LoginHelper.getUserId().equals(conversation.getAssignedUserId())) {
            return;
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<ChatConversation>()
            .eq(ChatConversation::getId, conversationId)
            .set(ChatConversation::getUnreadAgentCount, 0));
    }

    @Transactional(rollbackFor = Exception.class)
    protected ChatResponses.ConversationCreated createConversation(
        ChatChannel channel,
        ChatRequests.CreateConversation request
    ) {
        LocalDateTime now = LocalDateTime.now();
        ChatVisitor visitor = new ChatVisitor();
        visitor.setExternalId(trimToNull(request.getExternalId()));
        visitor.setVisitorName(defaultVisitorName(request.getVisitorName()));
        visitor.setPhone(trimToNull(request.getPhone()));
        visitor.setEmail(trimToNull(request.getEmail()));
        visitor.setLastSeenAt(now);
        visitorMapper.insert(visitor);

        String visitorToken = randomToken();
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationNo(randomConversationNo());
        conversation.setChannelId(channel.getId());
        conversation.setSkillGroupId(channel.getSkillGroupId());
        conversation.setVisitorId(visitor.getId());
        conversation.setAiAgentId(Boolean.TRUE.equals(channel.getAiEnabled()) ? channel.getAiAgentId() : null);
        conversation.setAccessTokenHash(hash(visitorToken));
        conversation.setStatus(Boolean.TRUE.equals(channel.getAiEnabled()) && channel.getAiAgentId() != null ? "AI_SERVING" : "QUEUING");
        conversation.setPriority(0);
        conversation.setQueuedAt(now);
        conversation.setLastMessageAt(now);
        conversation.setUnreadAgentCount(0);
        conversation.setUnreadVisitorCount(0);
        conversationMapper.insert(conversation);

        if (hasText(channel.getWelcomeMessage())) {
            insertMessage(conversation.getId(), "SYSTEM", null, channel.getChannelName(), channel.getWelcomeMessage(), null, now);
        }
        if (hasText(request.getInitialMessage())) {
            ChatRequests.SendMessage initial = new ChatRequests.SendMessage();
            initial.setContent(request.getInitialMessage());
            sendVisitorMessage(conversation, initial);
        }
        return new ChatResponses.ConversationCreated(
            conversation.getId(),
            conversation.getConversationNo(),
            visitorToken,
            conversation.getStatus()
        );
    }

    @Transactional(rollbackFor = Exception.class)
    protected ChatResponses.Message sendVisitorMessage(ChatConversation conversation, ChatRequests.SendMessage request) {
        if (!OPEN_STATUSES.contains(conversation.getStatus())) {
            throw new ServiceException("本次会话已结束，请重新发起咨询");
        }
        ChatMessage existing = findDuplicateMessage(conversation.getId(), "VISITOR", request.getClientMessageId());
        if (existing != null) {
            return toMessage(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        ChatVisitor visitor = visitorMapper.selectById(conversation.getVisitorId());
        ChatMessage message = insertMessage(
            conversation.getId(),
            "VISITOR",
            visitor == null ? null : visitor.getId(),
            visitor == null ? "访客" : visitor.getVisitorName(),
            request.getContent().trim(),
            trimToNull(request.getClientMessageId()),
            now
        );
        conversationMapper.update(null, new LambdaUpdateWrapper<ChatConversation>()
            .eq(ChatConversation::getId, conversation.getId())
            .set(ChatConversation::getLastMessageAt, now)
            .setSql("unread_agent_count = unread_agent_count + 1"));
        touchVisitor(conversation.getVisitorId());
        if ("AI_SERVING".equals(conversation.getStatus()) && conversation.getAiAgentId() != null) {
            submitAiReplyAfterCommit(conversation.getTenantId(), conversation.getId(), request.getContent().trim(), now);
        }
        return toMessage(message);
    }

    private void submitAiReplyAfterCommit(String tenantId, Long conversationId, String visitorText, LocalDateTime visitorMessageAt) {
        Runnable task = () -> chatAiReplyExecutor.execute(() -> handleAiReplyAsync(tenantId, conversationId, visitorText, visitorMessageAt));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    private void handleAiReplyAsync(String tenantId, Long conversationId, String visitorText, LocalDateTime visitorMessageAt) {
        synchronized (aiReplyLock(conversationId)) {
            TenantHelper.dynamic(tenantId, () -> {
                ChatConversation latest = conversationMapper.selectById(conversationId);
                if (latest == null || !"AI_SERVING".equals(latest.getStatus()) || latest.getAiAgentId() == null) {
                    return null;
                }
                handleAiReply(latest, visitorText, visitorMessageAt);
                return null;
            });
        }
    }

    private void handleAiReply(ChatConversation conversation, String visitorText, LocalDateTime visitorMessageAt) {
        try {
            AiIntentRecognitionResponse intent = recognizeIntent(conversation.getAiAgentId(), visitorText);
            if (intent.isMatched() && "TRANSFER_ONLINE_SERVICE".equals(intent.getActionType())) {
                transferToOnlineService(conversation, intent, visitorMessageAt);
                return;
            }
            if (intent.isMatched() && "CHAT_REPLY".equals(intent.getActionType()) && hasText(intent.getResponseTemplate())) {
                insertAiMessage(conversation, intent.getResponseTemplate().trim(), visitorMessageAt);
                return;
            }
            AiChatTurnResult result = aiAgentApplicationService.chatOnce(
                conversation.getAiAgentId(),
                conversation.getAiConversationId(),
                visitorText
            );
            if (!isAiServing(conversation.getId())) {
                return;
            }
            conversation.setAiConversationId(result.conversationId());
            conversationMapper.update(null, new LambdaUpdateWrapper<ChatConversation>()
                .eq(ChatConversation::getId, conversation.getId())
                .eq(ChatConversation::getStatus, "AI_SERVING")
                .set(ChatConversation::getAiConversationId, result.conversationId()));
            insertAiMessage(conversation, result.answer(), visitorMessageAt);
        } catch (Exception exception) {
            log.warn("在线客服 AI 回复失败，conversationId={}，aiAgentId={}，error={}",
                conversation.getId(), conversation.getAiAgentId(), exception.getMessage(), exception);
            if (!isAiServing(conversation.getId())) {
                return;
            }
            insertAiMessage(conversation, "当前 AI 接待暂时不可用，已为您转接人工客服，请稍候。", visitorMessageAt);
            transferToHumanQueue(conversation, conversation.getSkillGroupId(), visitorMessageAt);
        }
    }

    private AiIntentRecognitionResponse recognizeIntent(Long aiAgentId, String text) {
        AiIntentRecognitionRequest request = new AiIntentRecognitionRequest();
        request.setAgentId(aiAgentId);
        request.setText(text);
        return aiIntentApplicationService.recognize(request);
    }

    private void transferToOnlineService(ChatConversation conversation, AiIntentRecognitionResponse intent, LocalDateTime now) {
        String reply = hasText(intent.getResponseTemplate()) ? intent.getResponseTemplate().trim() : "正在为您转接人工客服，请稍候。";
        if (insertAiMessage(conversation, reply, now) == null) {
            return;
        }
        if (!transferToHumanQueue(conversation, skillGroupId(intent.getActionConfigJson(), conversation.getSkillGroupId()), now)) {
            return;
        }
        insertMessage(conversation.getId(), "SYSTEM", null, "系统", "AI 已转接人工客服，等待坐席接入。", null, now.plusNanos(2_000_000));
    }

    private boolean transferToHumanQueue(ChatConversation conversation, Long skillGroupId, LocalDateTime now) {
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<ChatConversation>()
            .eq(ChatConversation::getId, conversation.getId())
            .eq(ChatConversation::getStatus, "AI_SERVING")
            .set(ChatConversation::getStatus, "QUEUING")
            .set(ChatConversation::getSkillGroupId, skillGroupId)
            .set(ChatConversation::getQueuedAt, now)
            .set(ChatConversation::getLastMessageAt, now)
            .setSql("unread_agent_count = unread_agent_count + 1"));
        if (updated <= 0) {
            return false;
        }
        conversation.setStatus("QUEUING");
        conversation.setSkillGroupId(skillGroupId);
        return true;
    }

    private ChatMessage insertAiMessage(ChatConversation conversation, String content, LocalDateTime baseTime) {
        if (!isAiServing(conversation.getId())) {
            return null;
        }
        LocalDateTime sentAt = LocalDateTime.now();
        if (!sentAt.isAfter(baseTime)) {
            sentAt = baseTime.plusNanos(1_000_000);
        }
        ChatMessage message = insertMessage(conversation.getId(), "AI", conversation.getAiAgentId(), "AI助手", content, null, sentAt);
        conversationMapper.update(null, new LambdaUpdateWrapper<ChatConversation>()
            .eq(ChatConversation::getId, conversation.getId())
            .set(ChatConversation::getLastMessageAt, sentAt)
            .setSql("unread_visitor_count = unread_visitor_count + 1"));
        return message;
    }

    private boolean isAiServing(Long conversationId) {
        ChatConversation latest = conversationMapper.selectById(conversationId);
        return latest != null && "AI_SERVING".equals(latest.getStatus());
    }

    private Long skillGroupId(String actionConfigJson, Long fallback) {
        if (StringUtils.isBlank(actionConfigJson)) {
            return fallback;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(actionConfigJson);
            String value = node.path("skillGroupId").asText();
            return StringUtils.isBlank(value) ? fallback : Long.valueOf(value);
        } catch (Exception exception) {
            log.warn("在线客服转人工意图参数解析失败，actionConfigJson={}，error={}", actionConfigJson, exception.getMessage());
            return fallback;
        }
    }

    private ChatChannel findPublicChannel(String channelKey) {
        ChatChannel channel = TenantHelper.ignore(() -> channelMapper.selectOne(new LambdaQueryWrapper<ChatChannel>()
            .eq(ChatChannel::getChannelKey, channelKey)
            .eq(ChatChannel::getEnabled, true)
            .last("LIMIT 1")));
        if (channel == null) {
            throw new ServiceException("在线客服渠道不存在或已停用");
        }
        return channel;
    }

    private ChatConversation findPublicConversation(Long conversationId) {
        ChatConversation conversation = TenantHelper.ignore(() -> conversationMapper.selectById(conversationId));
        if (conversation == null) {
            throw new ServiceException("在线客服会话不存在");
        }
        return conversation;
    }

    private ChatConversation requireConversation(Long conversationId) {
        ChatConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ServiceException("在线客服会话不存在");
        }
        return conversation;
    }

    private ChatConversation requireOwnedActiveConversation(Long conversationId) {
        ChatConversation conversation = requireConversation(conversationId);
        if (!"ACTIVE".equals(conversation.getStatus())) {
            throw new ServiceException("会话当前不在服务中");
        }
        ensureOwned(conversation);
        return conversation;
    }

    private ChatConversation requireClosableConversation(Long conversationId) {
        ChatConversation conversation = requireConversation(conversationId);
        if (!OPEN_STATUSES.contains(conversation.getStatus())) {
            throw new ServiceException("会话已结束，不能重复结束");
        }
        if ("ACTIVE".equals(conversation.getStatus())) {
            ensureOwned(conversation);
        }
        return conversation;
    }

    private void ensureOwned(ChatConversation conversation) {
        if (!LoginHelper.getUserId().equals(conversation.getAssignedUserId())) {
            throw new ServiceException("该会话不属于当前坐席");
        }
    }

    private void validateVisitorToken(ChatConversation conversation, String visitorToken) {
        if (!hasText(visitorToken) || !constantTimeEquals(conversation.getAccessTokenHash(), hash(visitorToken))) {
            throw new ServiceException("访客会话凭证无效");
        }
    }

    private void validateOrigin(ChatChannel channel, String origin) {
        if (!hasText(origin) || !hasText(channel.getAllowedOrigins())) {
            return;
        }
        String normalizedOrigin = normalizeOrigin(origin);
        boolean allowed = List.of(channel.getAllowedOrigins().split("[,\\r\\n]+")).stream()
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .anyMatch(item -> "*".equals(item) || normalizeOrigin(item).equalsIgnoreCase(normalizedOrigin));
        if (!allowed) {
            throw new ServiceException("当前网站未被该在线客服渠道授权");
        }
    }

    private ChatMessage insertMessage(
        Long conversationId,
        String senderType,
        Long senderId,
        String senderName,
        String content,
        String clientMessageId,
        LocalDateTime sentAt
    ) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setSenderType(senderType);
        message.setSenderId(senderId);
        message.setSenderName(senderName);
        message.setMessageType("TEXT");
        message.setContent(content);
        message.setClientMessageId(clientMessageId);
        message.setSentAt(sentAt);
        messageMapper.insert(message);
        return message;
    }

    private ChatMessage findDuplicateMessage(Long conversationId, String senderType, String clientMessageId) {
        if (!hasText(clientMessageId)) {
            return null;
        }
        return messageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
            .eq(ChatMessage::getConversationId, conversationId)
            .eq(ChatMessage::getSenderType, senderType)
            .eq(ChatMessage::getClientMessageId, clientMessageId)
            .last("LIMIT 1"));
    }

    private ChatResponses.Conversation toConversation(ChatConversation conversation) {
        ChatChannel channel = channelMapper.selectById(conversation.getChannelId());
        ChatVisitor visitor = visitorMapper.selectById(conversation.getVisitorId());
        return new ChatResponses.Conversation(
            conversation.getId(),
            conversation.getConversationNo(),
            conversation.getChannelId(),
            channel == null ? null : channel.getChannelName(),
            conversation.getSkillGroupId(),
            conversation.getAiAgentId(),
            conversation.getVisitorId(),
            visitor == null ? null : visitor.getVisitorName(),
            visitor == null ? null : visitor.getPhone(),
            visitor == null ? null : visitor.getEmail(),
            conversation.getStatus(),
            conversation.getPriority(),
            conversation.getAssignedUserId(),
            conversation.getAssignedUserName(),
            conversation.getCustomerId(),
            conversation.getTicketId(),
            conversation.getQueuedAt(),
            conversation.getAssignedAt(),
            conversation.getClosedAt(),
            conversation.getLastMessageAt(),
            conversation.getUnreadAgentCount(),
            conversation.getUnreadVisitorCount()
        );
    }

    private ChatResponses.Message toMessage(ChatMessage message) {
        return new ChatResponses.Message(
            message.getId(),
            message.getConversationId(),
            message.getSenderType(),
            message.getSenderId(),
            message.getSenderName(),
            message.getMessageType(),
            message.getContent(),
            message.getSentAt()
        );
    }

    private void touchVisitor(Long visitorId) {
        visitorMapper.update(null, new LambdaUpdateWrapper<ChatVisitor>()
            .eq(ChatVisitor::getId, visitorId)
            .set(ChatVisitor::getLastSeenAt, LocalDateTime.now()));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomConversationNo() {
        return "CHAT" + NUMBER_TIME.format(LocalDateTime.now()) + String.format(Locale.ROOT, "%04d", secureRandom.nextInt(10000));
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII),
            right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static String normalizeOrigin(String origin) {
        String value = origin.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String defaultVisitorName(String name) {
        return hasText(name) ? name.trim() : "访客";
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Object aiReplyLock(Long conversationId) {
        return aiReplyLocks[Math.floorMod(conversationId.hashCode(), aiReplyLocks.length)];
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[AI_REPLY_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }
}
