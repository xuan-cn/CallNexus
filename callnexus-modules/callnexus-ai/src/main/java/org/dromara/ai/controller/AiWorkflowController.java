package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiAgentWorkflowBindingRequest;
import org.dromara.ai.domain.request.AiWorkflowDraftRequest;
import org.dromara.ai.domain.request.AiWorkflowRequest;
import org.dromara.ai.domain.request.AiWorkflowTestInputRequest;
import org.dromara.ai.domain.request.AiWorkflowTestStartRequest;
import org.dromara.ai.domain.response.AiAgentWorkflowBindingResponse;
import org.dromara.ai.domain.response.AiWorkflowResponse;
import org.dromara.ai.domain.response.AiWorkflowValidationResponse;
import org.dromara.ai.domain.response.AiWorkflowVersionResponse;
import org.dromara.ai.domain.response.AiWorkflowTestExecutionResponse;
import org.dromara.ai.service.AiWorkflowApplicationService;
import org.dromara.ai.service.AiWorkflowRuntimeService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai-workflows")
@RequiredArgsConstructor
public class AiWorkflowController {
    private final AiWorkflowApplicationService service;
    private final AiWorkflowRuntimeService runtimeService;

    @GetMapping
    @SaCheckPermission("callcenter:ai-workflow:list")
    public R<List<AiWorkflowResponse>> list() {
        return R.ok(service.workflows());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:ai-workflow:query")
    public R<AiWorkflowResponse> get(@PathVariable Long id) {
        return R.ok(service.workflow(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:ai-workflow:create")
    public R<Long> create(@Valid @RequestBody AiWorkflowRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ai-workflow:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AiWorkflowRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:ai-workflow:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @PutMapping("/{id}/{action:enable|disable}")
    @SaCheckPermission("callcenter:ai-workflow:edit")
    public R<Void> status(@PathVariable Long id, @PathVariable String action) {
        service.setEnabled(id, "enable".equals(action));
        return R.ok();
    }

    @GetMapping("/{id}/versions")
    @SaCheckPermission("callcenter:ai-workflow:query")
    public R<List<AiWorkflowVersionResponse>> versions(@PathVariable Long id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/draft")
    @SaCheckPermission("callcenter:ai-workflow:query")
    public R<AiWorkflowVersionResponse> draft(@PathVariable Long id) {
        return R.ok(service.draft(id));
    }

    @PutMapping("/{id}/draft")
    @SaCheckPermission("callcenter:ai-workflow:edit")
    public R<Long> saveDraft(@PathVariable Long id, @Valid @RequestBody AiWorkflowDraftRequest request) {
        return R.ok(service.saveDraft(id, request));
    }

    @PostMapping("/{id}/validate")
    @SaCheckPermission("callcenter:ai-workflow:edit")
    public R<AiWorkflowValidationResponse> validate(@PathVariable Long id) {
        return R.ok(service.validateDraft(id));
    }

    @PostMapping("/{id}/publish")
    @SaCheckPermission("callcenter:ai-workflow:publish")
    public R<AiWorkflowVersionResponse> publish(@PathVariable Long id) {
        return R.ok(service.publish(id));
    }

    @PostMapping("/{id}/test-executions")
    @SaCheckPermission("callcenter:ai-workflow:test")
    public R<AiWorkflowTestExecutionResponse> startTest(@PathVariable Long id,
                                                        @RequestBody AiWorkflowTestStartRequest request) {
        return R.ok(runtimeService.startTest(id, request));
    }

    @GetMapping("/test-executions/{executionId}")
    @SaCheckPermission("callcenter:ai-workflow:test")
    public R<AiWorkflowTestExecutionResponse> testExecution(@PathVariable String executionId) {
        return R.ok(runtimeService.execution(executionId));
    }

    @PostMapping("/test-executions/{executionId}/inputs")
    @SaCheckPermission("callcenter:ai-workflow:test")
    public R<AiWorkflowTestExecutionResponse> testInput(@PathVariable String executionId,
                                                        @Valid @RequestBody AiWorkflowTestInputRequest request) {
        return R.ok(runtimeService.input(executionId, request));
    }

    @DeleteMapping("/test-executions/{executionId}")
    @SaCheckPermission("callcenter:ai-workflow:test")
    public R<Void> terminateTest(@PathVariable String executionId) {
        runtimeService.terminate(executionId, "用户结束测试");
        return R.ok();
    }

    @GetMapping("/agents/{agentId}/bindings")
    @SaCheckPermission("callcenter:ai-agent:query")
    public R<List<AiAgentWorkflowBindingResponse>> bindings(@PathVariable Long agentId) {
        return R.ok(service.agentBindings(agentId));
    }

    @PutMapping("/agents/{agentId}/bindings")
    @SaCheckPermission("callcenter:ai-agent:update")
    public R<Void> saveBinding(@PathVariable Long agentId,
                               @Valid @RequestBody AiAgentWorkflowBindingRequest request) {
        service.saveAgentBinding(agentId, request);
        return R.ok();
    }

    @DeleteMapping("/agents/{agentId}/bindings/{sceneType}")
    @SaCheckPermission("callcenter:ai-agent:update")
    public R<Void> deleteBinding(@PathVariable Long agentId, @PathVariable String sceneType) {
        service.deleteAgentBinding(agentId, sceneType);
        return R.ok();
    }
}
