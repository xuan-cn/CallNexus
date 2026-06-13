package org.dromara.resource.media.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.resource.media.domain.request.AgentResultRequest;
import org.dromara.resource.media.domain.response.AgentTaskResponse;
import org.dromara.resource.media.service.MediaPublicationService;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/internal/freeswitch/media-agent")
@RequiredArgsConstructor
public class MediaAgentInternalController {
    private static final String NODE_HEADER = "X-CallNexus-Node-Code";
    private static final String TOKEN_HEADER = "X-CallNexus-Node-Token";
    private final MediaPublicationService service;

    @PostMapping("/heartbeat")
    public R<Void> heartbeat(@RequestHeader(NODE_HEADER) String nodeCode, @RequestHeader(TOKEN_HEADER) String token,
                             @RequestParam(required = false) String agentVersion) {
        service.heartbeat(nodeCode, token, agentVersion); return R.ok();
    }
    @PostMapping("/tasks/claim")
    public R<AgentTaskResponse> claim(@RequestHeader(NODE_HEADER) String nodeCode, @RequestHeader(TOKEN_HEADER) String token) {
        return R.ok(service.claim(nodeCode, token));
    }
    @GetMapping("/tasks/{taskId}/source")
    public void source(@PathVariable Long taskId, @RequestHeader(NODE_HEADER) String nodeCode,
                       @RequestHeader(TOKEN_HEADER) String token, @RequestParam String leaseToken,
                       HttpServletResponse response) throws IOException {
        service.downloadSource(taskId, nodeCode, token, leaseToken, response);
    }
    @PostMapping("/tasks/{taskId}/result")
    public R<Void> result(@PathVariable Long taskId, @RequestHeader(NODE_HEADER) String nodeCode,
                          @RequestHeader(TOKEN_HEADER) String token, @Valid @RequestBody AgentResultRequest request) {
        service.report(taskId, nodeCode, token, request); return R.ok();
    }
}
