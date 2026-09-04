package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiIntentGroupRequest;
import org.dromara.ai.domain.response.AiIntentGroupResponse;
import org.dromara.ai.service.AiIntentGroupApplicationService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/intent-groups")
@RequiredArgsConstructor
public class AiIntentGroupController {
    private final AiIntentGroupApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-intent:list")
    public R<List<AiIntentGroupResponse>> list() {
        return R.ok(service.groups());
    }

    @PostMapping
    @SaCheckPermission("callcenter:ai-intent:create")
    public R<Long> create(@Valid @RequestBody AiIntentGroupRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ai-intent:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AiIntentGroupRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:ai-intent:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
