package org.dromara.call.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.request.VoiceMailHandleRequest;
import org.dromara.call.domain.request.VoiceMailMessagePageQuery;
import org.dromara.call.domain.response.VoiceMailMessageResponse;
import org.dromara.call.service.VoiceMailMessageApplicationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/voicemail-messages")
@RequiredArgsConstructor
public class VoiceMailMessageController {
    private final VoiceMailMessageApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:voicemail:list")
    public TableDataInfo<VoiceMailMessageResponse> page(VoiceMailMessagePageQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:voicemail:query")
    public R<VoiceMailMessageResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PutMapping("/{id}/handle")
    @SaCheckPermission("callcenter:voicemail:handle")
    public R<Void> handle(@PathVariable Long id, @Valid @RequestBody VoiceMailHandleRequest request) {
        service.handle(id, request);
        return R.ok();
    }
}
