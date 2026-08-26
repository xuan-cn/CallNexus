package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.response.SpeechProviderDefinitionResponse;
import org.dromara.ai.speech.definition.SpeechProviderDefinitionRegistry;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/speech-provider-definitions")
@RequiredArgsConstructor
public class SpeechProviderDefinitionController {

    private final SpeechProviderDefinitionRegistry registry;

    @GetMapping
    @SaCheckPermission("callcenter:ai-speech:list")
    public R<List<SpeechProviderDefinitionResponse>> list() {
        return R.ok(registry.list().stream().map(SpeechProviderDefinitionResponse::from).toList());
    }

    @GetMapping("/{providerType}")
    @SaCheckPermission("callcenter:ai-speech:list")
    public R<SpeechProviderDefinitionResponse> get(@PathVariable String providerType) {
        return R.ok(SpeechProviderDefinitionResponse.from(registry.get(providerType)));
    }
}
