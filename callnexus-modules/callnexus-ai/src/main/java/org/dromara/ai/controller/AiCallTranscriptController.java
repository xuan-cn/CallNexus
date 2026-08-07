package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.response.AiCallTranscriptResponse;
import org.dromara.ai.service.AiCallTranscriptStreamService;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai/call-transcripts")
@RequiredArgsConstructor
public class AiCallTranscriptController {

    private final AiSpeechApplicationService service;
    private final AiCallTranscriptStreamService streamService;

    @GetMapping("/{callSessionId}")
    @SaCheckPermission("callcenter:ai-speech:list")
    public AiCallTranscriptResponse get(@PathVariable Long callSessionId) {
        return service.callTranscript(callSessionId);
    }

    @GetMapping(value = "/{callSessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaCheckPermission("callcenter:ai-speech:list")
    public SseEmitter stream(@PathVariable Long callSessionId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return streamService.subscribe(TenantHelper.getTenantId(), callSessionId);
    }

    @PostMapping("/{callSessionId}/transcribe")
    @SaCheckPermission("callcenter:ai-speech:transcribe")
    public AiCallTranscriptResponse transcribe(@PathVariable Long callSessionId) {
        return service.transcribeCallRecording(callSessionId);
    }
}
