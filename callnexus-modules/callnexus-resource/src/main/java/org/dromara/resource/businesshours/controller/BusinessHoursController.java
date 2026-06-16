package org.dromara.resource.businesshours.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.resource.businesshours.domain.request.BusinessHoursEvaluateRequest;
import org.dromara.resource.businesshours.domain.request.BusinessHoursPlanRequest;
import org.dromara.resource.businesshours.domain.response.BusinessHoursEvaluation;
import org.dromara.resource.businesshours.domain.response.BusinessHoursPlanResponse;
import org.dromara.resource.businesshours.service.BusinessHoursApplicationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/business-hours-plans")
@RequiredArgsConstructor
public class BusinessHoursController {
    private final BusinessHoursApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:business-hours:list")
    public R<List<BusinessHoursPlanResponse>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:business-hours:query")
    public R<BusinessHoursPlanResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:business-hours:create")
    public R<Long> create(@Valid @RequestBody BusinessHoursPlanRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:business-hours:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody BusinessHoursPlanRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:business-hours:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/evaluate")
    @SaCheckPermission("callcenter:business-hours:query")
    public R<BusinessHoursEvaluation> evaluate(@PathVariable Long id, @RequestBody(required = false) BusinessHoursEvaluateRequest request) {
        return R.ok(service.evaluate(id, request == null ? null : request.getEvaluatedAt()));
    }
}
