package org.dromara.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.request.SkillGroupRequest;
import org.dromara.agent.domain.response.SkillGroupResponse;
import org.dromara.agent.service.SkillGroupService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/skill-groups")
@RequiredArgsConstructor
public class SkillGroupController {
    private final SkillGroupService service;

    @GetMapping
    @SaCheckPermission("callcenter:skill-group:list")
    public R<List<SkillGroupResponse>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:skill-group:query")
    public R<SkillGroupResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:skill-group:create")
    public R<Long> create(@Valid @RequestBody SkillGroupRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:skill-group:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody SkillGroupRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:skill-group:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
