package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiSpeechTemplateRequest;
import org.dromara.ai.domain.response.AiSpeechTemplateResponse;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/speech-templates")
@RequiredArgsConstructor
public class AiSpeechTemplateController {
    private final AiSpeechApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-speech:list")
    public R<List<AiSpeechTemplateResponse>> list() {
        return R.ok(service.templates());
    }

    @PostMapping
    @SaCheckPermission("callcenter:ai-speech:create")
    public R<Long> create(@Valid @RequestBody AiSpeechTemplateRequest request) {
        return R.ok(service.createTemplate(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ai-speech:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AiSpeechTemplateRequest request) {
        service.updateTemplate(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:ai-speech:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.deleteTemplate(id);
        return R.ok();
    }
}
