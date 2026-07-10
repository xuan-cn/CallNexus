package org.dromara.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiRealtimeTtsRequest;
import org.dromara.ai.domain.response.AiRealtimeAsrResponse;
import org.dromara.ai.service.AiRealtimeAsrInternalService;
import org.dromara.ai.service.AiRealtimeTtsInternalService;
import org.dromara.common.core.domain.R;
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
    private static final String TENANT_HEADER = "X-CallNexus-Tenant-Id";
    private static final String CALL_UUID_HEADER = "X-CallNexus-Call-Uuid";
    private static final String AGENT_ID_HEADER = "X-CallNexus-Agent-Id";
    private static final String FLOW_ID_HEADER = "X-CallNexus-Flow-Id";
    private static final String LANGUAGE_HEADER = "X-CallNexus-Language";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    private final AiRealtimeTtsInternalService service;
    private final AiRealtimeAsrInternalService asrService;

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

    @PostMapping("/asr")
    public R<AiRealtimeAsrResponse> asr(@RequestHeader(NODE_HEADER) String nodeCode,
                                        @RequestHeader(TOKEN_HEADER) String token,
                                        @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
                                        @RequestHeader(value = CONTENT_TYPE_HEADER, required = false) String contentType,
                                        @RequestHeader(value = LANGUAGE_HEADER, required = false) String language,
                                        @RequestHeader(value = CALL_UUID_HEADER, required = false) String callUuid,
                                        @RequestHeader(value = AGENT_ID_HEADER, required = false) Long agentId,
                                        @RequestHeader(value = FLOW_ID_HEADER, required = false) Long flowId,
                                        @RequestBody byte[] audio) {
        return R.ok(asrService.transcribe(nodeCode, token, tenantId, contentType, language, callUuid, agentId, flowId, audio));
    }
}