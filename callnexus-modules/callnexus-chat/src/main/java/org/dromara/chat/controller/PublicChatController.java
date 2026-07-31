package org.dromara.chat.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.chat.domain.request.ChatRequests;
import org.dromara.chat.domain.response.ChatResponses;
import org.dromara.chat.service.ChatConversationService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@SaIgnore
@RestController
@RequestMapping("/public/chat")
@RequiredArgsConstructor
public class PublicChatController {
    private final ChatConversationService service;

    @GetMapping("/channels/{channelKey}/bootstrap")
    public R<ChatResponses.Bootstrap> bootstrap(@PathVariable String channelKey, HttpServletRequest request) {
        return R.ok(service.bootstrap(channelKey, request.getHeader("Origin")));
    }

    @PostMapping("/channels/{channelKey}/conversations")
    public R<ChatResponses.ConversationCreated> create(
        @PathVariable String channelKey,
        @Valid @RequestBody ChatRequests.CreateConversation body,
        HttpServletRequest request
    ) {
        return R.ok(service.createPublicConversation(channelKey, request.getHeader("Origin"), body));
    }

    @GetMapping("/conversations/{id}/messages")
    public R<List<ChatResponses.Message>> messages(
        @PathVariable Long id,
        @RequestHeader("X-Visitor-Token") String visitorToken,
        @RequestParam(required = false) Long afterId
    ) {
        return R.ok(service.listPublicMessages(id, visitorToken, afterId));
    }

    @PostMapping("/conversations/{id}/messages")
    public R<ChatResponses.Message> send(
        @PathVariable Long id,
        @RequestHeader("X-Visitor-Token") String visitorToken,
        @Valid @RequestBody ChatRequests.SendMessage body
    ) {
        return R.ok(service.sendPublicMessage(id, visitorToken, body));
    }
}
