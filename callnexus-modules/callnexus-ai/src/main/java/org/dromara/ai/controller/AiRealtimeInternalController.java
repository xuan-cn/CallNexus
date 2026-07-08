package org.dromara.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiRealtimeTtsRequest;
import org.dromara.ai.service.AiRealtimeTtsInternalService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/ai/realtime")
@RequiredArgsConstructor
public class AiRealtimeInternalController {
    private static final String NODE_HEADER = "X-CallNexus-Node-Code";
    private static final String TOKEN_HEADER = "X-CallNexus-Node-Token";

    private final AiRealtimeTtsInternalService service;

    @PostMapping("/tts")
    public ResponseEntity<byte[]> tts(@RequestHeader(NODE_HEADER) String nodeCode,
                                      @RequestHeader(TOKEN_HEADER) String token,
                                      @Valid @RequestBody AiRealtimeTtsRequest request) {
        AiRealtimeTtsInternalService.RealtimeTtsAudio audio = service.generate(nodeCode, token, request);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, audio.contentType())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(audio.bytes());
    }
}
