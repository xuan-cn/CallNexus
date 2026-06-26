package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.response.AiCallTranscriptResponse;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/call-transcripts")
@RequiredArgsConstructor
public class AiCallTranscriptController {

    private final AiSpeechApplicationService service;

    @GetMapping("/{callSessionId}")
    @SaCheckPermission("callcenter:ai-speech:list")
    public AiCallTranscriptResponse get(@PathVariable Long callSessionId) {
        return service.callTranscript(callSessionId);
    }

    @PostMapping("/{callSessionId}/transcribe")
    @SaCheckPermission("callcenter:ai-speech:transcribe")
    public AiCallTranscriptResponse transcribe(@PathVariable Long callSessionId) {
        return service.transcribeCallRecording(callSessionId);
    }
}
