package org.dromara.ivr.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.ivr.domain.IvrFlowRequest;
import org.dromara.ivr.domain.IvrFlowResponse;
import org.dromara.ivr.domain.IvrFlowVersionResponse;
import org.dromara.ivr.service.IvrFlowService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ivr-flows")
@RequiredArgsConstructor
public class IvrFlowController {
    private final IvrFlowService service;

    @GetMapping
    @SaCheckPermission("callcenter:ivr-flow:list")
    public R<List<IvrFlowResponse>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:ivr-flow:query")
    public R<IvrFlowResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @GetMapping("/{id}/versions")
    @SaCheckPermission("callcenter:ivr-flow:query")
    public R<List<IvrFlowVersionResponse>> versions(@PathVariable Long id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/versions/{versionNo}")
    @SaCheckPermission("callcenter:ivr-flow:query")
    public R<IvrFlowVersionResponse> version(@PathVariable Long id, @PathVariable Integer versionNo) {
        return R.ok(service.version(id, versionNo));
    }

    @PostMapping
    @SaCheckPermission("callcenter:ivr-flow:create")
    public R<Long> create(@Valid @RequestBody IvrFlowRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ivr-flow:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody IvrFlowRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/publish")
    @SaCheckPermission("callcenter:ivr-flow:publish")
    public R<Void> publish(@PathVariable Long id) {
        service.publish(id);
        return R.ok();
    }

    @PostMapping("/{id}/unpublish")
    @SaCheckPermission("callcenter:ivr-flow:publish")
    public R<Void> unpublish(@PathVariable Long id) {
        service.unpublish(id);
        return R.ok();
    }

    @PostMapping("/{id}/versions/{versionNo}/rollback")
    @SaCheckPermission("callcenter:ivr-flow:publish")
    public R<Void> rollback(@PathVariable Long id, @PathVariable Integer versionNo) {
        service.rollback(id, versionNo);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:ivr-flow:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
