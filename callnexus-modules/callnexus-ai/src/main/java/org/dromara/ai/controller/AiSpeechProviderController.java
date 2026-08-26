package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiSpeechProviderRequest;
import org.dromara.ai.domain.request.TtsTestRequest;
import org.dromara.ai.domain.response.AiSpeechProviderResponse;
import org.dromara.ai.domain.response.AsrTestResponse;
import org.dromara.ai.domain.response.TtsTestResponse;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.ai.speech.definition.SpeechCapability;
import org.dromara.common.core.domain.R;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping({"/api/v1/ai/speech-providers", "/api/v1/ai/tts-providers"})
@RequiredArgsConstructor
public class AiSpeechProviderController {
    private final AiSpeechApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-speech:list")
    public R<List<AiSpeechProviderResponse>> list() {
        return R.ok(service.providers());
    }

    @PostMapping
    @SaCheckPermission("callcenter:ai-speech:create")
    public R<Long> create(@Valid @RequestBody AiSpeechProviderRequest request) {
        return R.ok(service.createProvider(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ai-speech:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AiSpeechProviderRequest request) {
        service.updateProvider(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:ai-speech:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.deleteProvider(id);
        return R.ok();
    }

    @PostMapping("/preview/validate")
    @SaCheckPermission("callcenter:ai-speech:test")
    public R<org.dromara.ai.domain.response.SpeechProviderTestResponse> validate(
        @Valid @RequestBody AiSpeechProviderRequest request) {
        return R.ok(service.validateProviderConfiguration(request));
    }

    @PostMapping("/preview/connection-test")
    @SaCheckPermission("callcenter:ai-speech:test")
    public R<org.dromara.ai.domain.response.SpeechProviderTestResponse> previewConnectionTest(
        @Valid @RequestBody AiSpeechProviderRequest request) {
        return R.ok(service.testProviderConnection(request));
    }

    @PostMapping("/{id}/connection-test")
    @SaCheckPermission("callcenter:ai-speech:test")
    public R<org.dromara.ai.domain.response.SpeechProviderTestResponse> connectionTest(@PathVariable Long id) {
        return R.ok(service.testProviderConnection(id));
    }

    @PostMapping("/{id}/streaming-test")
    @SaCheckPermission("callcenter:ai-speech:test")
    public R<org.dromara.ai.domain.response.SpeechProviderTestResponse> streamingTest(
        @PathVariable Long id, @RequestParam SpeechCapability capability) {
        return R.ok(service.testStreamingProvider(id, capability));
    }

    @PostMapping({"/{id}/test", "/{id}/tts/test"})
    @SaCheckPermission("callcenter:ai-speech:test")
    public R<TtsTestResponse> testTts(@PathVariable Long id, @Valid @RequestBody TtsTestRequest request) {
        return R.ok(service.testProvider(id, request));
    }

    @GetMapping("/{id}/voices")
    @SaCheckPermission("callcenter:ai-speech:test")
    public R<List<String>> voices(@PathVariable Long id) {
        return R.ok(service.providerVoices(id));
    }

    @GetMapping("/{id}/catalog")
    @SaCheckPermission("callcenter:ai-speech:list")
    public R<org.dromara.ai.domain.response.SpeechProviderCatalogResponse> catalog(
        @PathVariable Long id, @RequestParam(defaultValue = "false") boolean refresh) {
        return R.ok(service.providerCatalog(id, refresh));
    }

    @PostMapping(value = {"/{id}/asr/test", "/{id}/recording-asr/test"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission("callcenter:ai-speech:test")
    public R<AsrTestResponse> testAsr(@PathVariable Long id,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestParam(required = false) String format,
                                     @RequestParam(required = false) Integer sampleRate) {
        return R.ok(service.testAsrProvider(id, file, format, sampleRate));
    }
}
