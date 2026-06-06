package org.dromara.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.request.*;
import org.dromara.agent.domain.response.AgentResponse;
import org.dromara.agent.service.AgentApplicationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {
    private final AgentApplicationService applicationService;

    @GetMapping
    @SaCheckPermission("callcenter:agent:list")
    public TableDataInfo<AgentResponse> page(AgentPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:agent:query")
    public R<AgentResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:agent:create")
    public R<Long> create(@Valid @RequestBody CreateAgentRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:agent:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateAgentRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:agent:delete")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }

    @PutMapping("/{id}/extension")
    @SaCheckPermission("callcenter:agent:bind-extension")
    public R<Void> bindExtension(@PathVariable Long id, @Valid @RequestBody BindAgentExtensionRequest request) {
        applicationService.bindExtension(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}/extension")
    @SaCheckPermission("callcenter:agent:bind-extension")
    public R<Void> unbindExtension(@PathVariable Long id) {
        applicationService.unbindExtension(id);
        return R.ok();
    }
}
