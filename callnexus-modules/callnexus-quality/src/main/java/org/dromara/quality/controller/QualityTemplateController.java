package org.dromara.quality.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.quality.domain.request.QualityTemplateRequest;
import org.dromara.quality.domain.response.QualityTemplateResponse;
import org.dromara.quality.service.QualityTemplateService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/quality/templates")
@RequiredArgsConstructor
public class QualityTemplateController {
    private final QualityTemplateService service;

    @GetMapping @SaCheckPermission("callcenter:quality-template:list")
    public R<List<QualityTemplateResponse>> list() { return R.ok(service.list()); }
    @GetMapping("/{id}") @SaCheckPermission("callcenter:quality-template:query")
    public R<QualityTemplateResponse> get(@PathVariable Long id) { return R.ok(service.get(id)); }
    @PostMapping @SaCheckPermission("callcenter:quality-template:create")
    public R<Long> create(@Valid @RequestBody QualityTemplateRequest request) { return R.ok(service.create(request)); }
    @PutMapping("/{id}") @SaCheckPermission("callcenter:quality-template:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody QualityTemplateRequest request) { service.update(id, request); return R.ok(); }
    @DeleteMapping("/{id}") @SaCheckPermission("callcenter:quality-template:delete")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
    @PostMapping("/{id}/publish") @SaCheckPermission("callcenter:quality-template:publish")
    public R<Long> publish(@PathVariable Long id) { return R.ok(service.publish(id)); }
}
