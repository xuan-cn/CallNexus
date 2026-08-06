package org.dromara.openapi.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.openapi.domain.response.OpenApiAgentActiveCallResponse;
import org.dromara.openapi.domain.response.OpenApiCallDetailResponse;
import org.dromara.openapi.domain.response.OpenApiCallResponse;
import org.dromara.openapi.domain.response.OpenApiRecordingResponse;
import org.dromara.openapi.domain.response.OpenApiTranscriptResponse;
import org.dromara.openapi.security.OpenApiContext;
import org.dromara.openapi.service.OpenApiCallQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/openapi/v1")
@RequiredArgsConstructor
public class OpenApiCallQueryController {
    private final OpenApiCallQueryService queryService;

    @GetMapping("/calls/active")
    public List<OpenApiCallResponse> listActiveCalls() {
        OpenApiContext.requireScope("call.read");
        return queryService.listActiveCalls();
    }

    @GetMapping("/calls/{businessCallId}")
    public OpenApiCallDetailResponse get(@PathVariable String businessCallId) {
        OpenApiContext.requireScope("call.read");
        return queryService.get(businessCallId);
    }

    @GetMapping("/calls/{businessCallId}/recordings")
    public List<OpenApiRecordingResponse> recordings(@PathVariable String businessCallId) {
        OpenApiContext.requireScope("call.read");
        return queryService.recordings(businessCallId);
    }

    @GetMapping("/calls/{businessCallId}/transcript")
    public OpenApiTranscriptResponse transcript(@PathVariable String businessCallId) {
        OpenApiContext.requireScope("call.read");
        return queryService.transcript(businessCallId);
    }

    @GetMapping("/agents/{agentId}/active-call")
    public OpenApiAgentActiveCallResponse getAgentActiveCall(@PathVariable Long agentId) {
        OpenApiContext.requireScope("call.read");
        return queryService.getAgentActiveCall(agentId);
    }
}
