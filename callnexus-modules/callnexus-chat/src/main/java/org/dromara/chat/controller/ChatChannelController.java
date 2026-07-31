package org.dromara.chat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.chat.domain.request.ChatRequests;
import org.dromara.chat.domain.response.ChatResponses;
import org.dromara.chat.service.ChatChannelService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.*;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/chat/channels")
@RequiredArgsConstructor
public class ChatChannelController {
    private final ChatChannelService service;

    @GetMapping
    @SaCheckPermission("callcenter:chat-channel:list")
    public TableDataInfo<ChatResponses.Channel> page(String channelName, Boolean enabled, PageQuery pageQuery) {
        return service.page(channelName, enabled, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:chat-channel:query")
    public R<ChatResponses.Channel> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:chat-channel:create")
    public R<Long> create(@Valid @RequestBody ChatRequests.SaveChannel request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:chat-channel:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ChatRequests.SaveChannel request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:chat-channel:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
