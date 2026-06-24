package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiTtsProviderRequest;
import org.dromara.ai.domain.request.TtsTestRequest;
import org.dromara.ai.domain.response.AiTtsProviderResponse;
import org.dromara.ai.domain.response.TtsTestResponse;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/tts-providers")
@RequiredArgsConstructor
public class AiTtsProviderController {
    private final AiSpeechApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-speech:list")
    public R<List<AiTtsProviderResponse>> list() {
        return R.ok(service.providers());
    }

    @PostMapping
    @SaCheckPermission("callcenter:ai-speech:create")
    public R<Long> create(@Valid @RequestBody AiTtsProviderRequest request) {
        return R.ok(service.createProvider(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ai-speech:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AiTtsProviderRequest request) {
        service.updateProvider(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:ai-speech:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.deleteProvider(id);
        return R.ok();
    }

    @PostMapping("/{id}/test")
    @SaCheckPermission("callcenter:ai-speech:test")
    public R<TtsTestResponse> test(@PathVariable Long id, @Valid @RequestBody TtsTestRequest request) {
        return R.ok(service.testProvider(id, request));
    }
}
