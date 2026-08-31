package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiTicketPolicyRequest;
import org.dromara.ai.domain.request.AiTicketPromptRequest;
import org.dromara.ai.domain.response.AiTicketPolicyResponse;
import org.dromara.ai.domain.response.AiTicketPromptResponse;
import org.dromara.ai.domain.response.AiTicketPromptValidationResponse;
import org.dromara.ai.domain.response.AiTicketPromptVersionResponse;
import org.dromara.ai.service.AiTicketPolicyApplicationService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/agents/{agentId}/ticket")
@RequiredArgsConstructor
public class AiTicketPolicyController {

    private final AiTicketPolicyApplicationService service;

    @GetMapping("/policy")
    @SaCheckPermission("callcenter:ai-agent:query")
    public R<AiTicketPolicyResponse> policy(@PathVariable Long agentId) {
        return R.ok(service.policy(agentId));
    }

    @PutMapping("/policy")
    @SaCheckPermission("callcenter:ai-agent:update")
    public R<Long> savePolicy(@PathVariable Long agentId, @Valid @RequestBody AiTicketPolicyRequest request) {
        return R.ok(service.savePolicy(agentId, request));
    }

    @GetMapping("/prompt")
    @SaCheckPermission("callcenter:ai-agent:query")
    public R<AiTicketPromptResponse> prompt(@PathVariable Long agentId) {
        return R.ok(service.prompt(agentId));
    }

    @PutMapping("/prompt/draft")
    @SaCheckPermission("callcenter:ai-agent:update")
    public R<Long> savePromptDraft(@PathVariable Long agentId, @Valid @RequestBody AiTicketPromptRequest request) {
        return R.ok(service.savePromptDraft(agentId, request));
    }

    @PostMapping("/prompt/validate")
    @SaCheckPermission("callcenter:ai-agent:query")
    public R<AiTicketPromptValidationResponse> validatePrompt(@PathVariable Long agentId,
                                                               @Valid @RequestBody AiTicketPromptRequest request) {
        return R.ok(service.validatePrompt(agentId, request));
    }

    @PostMapping("/prompt/publish")
    @SaCheckPermission("callcenter:ai-agent:update")
    public R<Void> publishPrompt(@PathVariable Long agentId) {
        service.publishPrompt(agentId);
        return R.ok();
    }

    @PostMapping("/prompt/restore-default")
    @SaCheckPermission("callcenter:ai-agent:update")
    public R<Long> restoreDefaultPrompt(@PathVariable Long agentId) {
        return R.ok(service.restoreDefaultPrompt(agentId));
    }

    @GetMapping("/prompt/versions")
    @SaCheckPermission("callcenter:ai-agent:query")
    public R<List<AiTicketPromptVersionResponse>> promptVersions(@PathVariable Long agentId) {
        return R.ok(service.promptVersions(agentId));
    }
}
