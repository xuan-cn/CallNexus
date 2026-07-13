package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.service.AiModelConfigurationService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiModelController {
    private final AiModelConfigurationService service;

    @GetMapping("/model-providers")
    @SaCheckPermission("callcenter:ai-model:list")
    public R<List<AiModelProviderResponse>> providers() {
        return R.ok(service.providers());
    }

    @PostMapping("/model-providers")
    @SaCheckPermission("callcenter:ai-model:create")
    public R<Long> createProvider(@Valid @RequestBody AiModelProviderRequest request) {
        return R.ok(service.createProvider(request));
    }

    @PutMapping("/model-providers/{id}")
    @SaCheckPermission("callcenter:ai-model:update")
    public R<Void> updateProvider(@PathVariable Long id, @Valid @RequestBody AiModelProviderRequest request) {
        service.updateProvider(id, request);
        return R.ok();
    }

    @DeleteMapping("/model-providers/{id}")
    @SaCheckPermission("callcenter:ai-model:delete")
    public R<Void> deleteProvider(@PathVariable Long id) {
        service.deleteProvider(id);
        return R.ok();
    }

    @PostMapping("/model-providers/{id}/test")
    @SaCheckPermission("callcenter:ai-model:test")
    public R<Map<String, Object>> testProvider(@PathVariable Long id) {
        return R.ok(service.testProvider(id));
    }

    @GetMapping("/models")
    @SaCheckPermission("callcenter:ai-model:list")
    public R<List<AiModelResponse>> models(@RequestParam(required = false) String capability) {
        return R.ok(service.models(capability));
    }

    @PostMapping("/models")
    @SaCheckPermission("callcenter:ai-model:create")
    public R<Long> createModel(@Valid @RequestBody AiModelRequest request) {
        return R.ok(service.createModel(request));
    }

    @PutMapping("/models/{id}")
    @SaCheckPermission("callcenter:ai-model:update")
    public R<Void> updateModel(@PathVariable Long id, @Valid @RequestBody AiModelRequest request) {
        service.updateModel(id, request);
        return R.ok();
    }

    @DeleteMapping("/models/{id}")
    @SaCheckPermission("callcenter:ai-model:delete")
    public R<Void> deleteModel(@PathVariable Long id) {
        service.deleteModel(id);
        return R.ok();
    }

    @PostMapping("/models/{id}/test")
    @SaCheckPermission("callcenter:ai-model:test")
    public R<Map<String, Object>> testModel(@PathVariable Long id) {
        return R.ok(service.testModel(id));
    }
}
