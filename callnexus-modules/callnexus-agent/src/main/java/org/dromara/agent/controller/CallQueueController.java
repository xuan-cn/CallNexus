package org.dromara.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.request.CallQueueRequest;
import org.dromara.agent.domain.response.CallQueueResponse;
import org.dromara.agent.service.CallQueueService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/call-queues")
@RequiredArgsConstructor
public class CallQueueController {
    private final CallQueueService service;

    @GetMapping
    @SaCheckPermission("callcenter:call-queue:list")
    public R<List<CallQueueResponse>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:call-queue:query")
    public R<CallQueueResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:call-queue:create")
    public R<Long> create(@Valid @RequestBody CallQueueRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:call-queue:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody CallQueueRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/sync")
    @SaCheckPermission("callcenter:call-queue:update")
    public R<Void> sync(@PathVariable Long id) {
        service.sync(id);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:call-queue:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
