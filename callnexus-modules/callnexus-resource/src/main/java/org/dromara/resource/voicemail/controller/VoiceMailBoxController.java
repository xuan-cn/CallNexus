package org.dromara.resource.voicemail.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.voicemail.domain.request.VoiceMailBoxPageQuery;
import org.dromara.resource.voicemail.domain.request.VoiceMailBoxRequest;
import org.dromara.resource.voicemail.domain.response.VoiceMailBoxResponse;
import org.dromara.resource.voicemail.service.VoiceMailBoxApplicationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/voicemail-boxes")
@RequiredArgsConstructor
public class VoiceMailBoxController {
    private final VoiceMailBoxApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:voicemail:list")
    public TableDataInfo<VoiceMailBoxResponse> page(VoiceMailBoxPageQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:voicemail:query")
    public R<VoiceMailBoxResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:voicemail-box:create")
    public R<Long> create(@Valid @RequestBody VoiceMailBoxRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:voicemail-box:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody VoiceMailBoxRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:voicemail-box:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
