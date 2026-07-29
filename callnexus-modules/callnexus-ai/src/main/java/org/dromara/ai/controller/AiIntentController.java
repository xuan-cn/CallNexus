package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.request.AiIntentRequest;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.domain.response.AiIntentResponse;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/intents")
@RequiredArgsConstructor
public class AiIntentController {
    private final AiIntentApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-intent:list")
    public R<List<AiIntentResponse>> list() {
        return R.ok(service.intents());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:ai-intent:query")
    public R<AiIntentResponse> detail(@PathVariable Long id) {
        return R.ok(service.intent(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:ai-intent:create")
    public R<Long> create(@Valid @RequestBody AiIntentRequest request) {
        return R.ok(service.createIntent(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ai-intent:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AiIntentRequest request) {
        service.updateIntent(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:ai-intent:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.deleteIntent(id);
        return R.ok();
    }

    @PostMapping("/recognize-test")
    @SaCheckPermission("callcenter:ai-intent:test")
    public R<AiIntentRecognitionResponse> recognize(@Valid @RequestBody AiIntentRecognitionRequest request) {
        return R.ok(service.recognize(request));
    }
}
