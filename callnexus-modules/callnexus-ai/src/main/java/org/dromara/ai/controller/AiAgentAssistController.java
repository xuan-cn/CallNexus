package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.response.AiAgentAssistDetailResponse;
import org.dromara.ai.domain.response.AiTicketDraftResponse;
import org.dromara.ai.domain.request.AiTicketDraftUpdateRequest;
import org.dromara.ai.service.AiAgentAssistService;
import org.dromara.ai.service.AiAgentAssistStreamService;
import org.dromara.common.core.domain.R;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/calls/{businessCallId}/agent-assist")
@RequiredArgsConstructor
public class AiAgentAssistController {

    private final AiAgentAssistService service;
    private final AiAgentAssistStreamService streamService;

    @GetMapping
    @SaCheckPermission("callcenter:customer:query")
    public R<AiAgentAssistDetailResponse> detail(@PathVariable String businessCallId) {
        return R.ok(service.detail(businessCallId));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaCheckPermission("callcenter:customer:query")
    public SseEmitter stream(@PathVariable String businessCallId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return streamService.subscribe(TenantHelper.getTenantId(), businessCallId);
    }

    @PostMapping("/suggestions/{suggestionId}/regenerate")
    @SaCheckPermission("callcenter:customer:query")
    public R<Void> regenerate(@PathVariable String businessCallId, @PathVariable Long suggestionId) {
        service.regenerate(businessCallId, suggestionId);
        return R.ok();
    }

    @PostMapping("/ticket-drafts/{draftId}/approve")
    @SaCheckPermission("callcenter:customer:query")
    public R<Long> approveTicketDraft(@PathVariable String businessCallId, @PathVariable Long draftId,
                                      @RequestParam Integer version) {
        return R.ok(service.approveTicketDraft(businessCallId, draftId, version));
    }

    @PutMapping("/ticket-drafts/{draftId}")
    @SaCheckPermission("callcenter:customer:query")
    public R<AiTicketDraftResponse> updateTicketDraft(@PathVariable String businessCallId,
                                                       @PathVariable Long draftId,
                                                       @Valid @RequestBody AiTicketDraftUpdateRequest request) {
        return R.ok(service.updateTicketDraft(businessCallId, draftId, request));
    }
}
