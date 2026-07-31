package org.dromara.chat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.chat.domain.request.ChatConversationQuery;
import org.dromara.chat.domain.request.ChatRequests;
import org.dromara.chat.domain.response.ChatResponses;
import org.dromara.chat.service.ChatConversationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.*;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/chat/conversations")
@RequiredArgsConstructor
public class ChatConversationController {
    private final ChatConversationService service;

    @GetMapping
    @SaCheckPermission("callcenter:chat-conversation:list")
    public TableDataInfo<ChatResponses.Conversation> page(ChatConversationQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:chat-conversation:list")
    public R<ChatResponses.ConversationDetail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/{id}/claim")
    @SaCheckPermission("callcenter:chat-conversation:claim")
    public R<Void> claim(@PathVariable Long id) {
        service.claim(id);
        return R.ok();
    }

    @PostMapping("/{id}/messages")
    @SaCheckPermission("callcenter:chat-conversation:reply")
    public R<ChatResponses.Message> send(@PathVariable Long id, @Valid @RequestBody ChatRequests.SendMessage request) {
        return R.ok(service.sendAgentMessage(id, request));
    }

    @PostMapping("/{id}/read")
    @SaCheckPermission("callcenter:chat-conversation:list")
    public R<Void> read(@PathVariable Long id) {
        service.markAgentRead(id);
        return R.ok();
    }

    @PostMapping("/{id}/close")
    @SaCheckPermission("callcenter:chat-conversation:close")
    public R<Void> close(@PathVariable Long id) {
        service.close(id);
        return R.ok();
    }
}
