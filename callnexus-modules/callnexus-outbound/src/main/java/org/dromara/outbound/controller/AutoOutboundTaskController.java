package org.dromara.outbound.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.outbound.domain.request.AutoOutboundSourceRequest;
import org.dromara.outbound.domain.request.AutoOutboundTaskRequest;
import org.dromara.outbound.domain.response.AutoOutboundMaterializeResponse;
import org.dromara.outbound.domain.response.AutoOutboundMemberResponse;
import org.dromara.outbound.domain.response.AutoOutboundSourceResponse;
import org.dromara.outbound.domain.response.AutoOutboundTaskResponse;
import org.dromara.outbound.domain.response.AutoOutboundMonitorResponse;
import org.dromara.outbound.service.AutoOutboundTaskService;
import org.dromara.outbound.service.AutoOutboundSchedulerService;
import org.dromara.outbound.service.AutoOutboundDialerService;
import org.dromara.outbound.service.model.AutoOutboundDialerResult;
import org.dromara.outbound.service.model.AutoOutboundSchedulerResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auto-outbound-tasks")
@RequiredArgsConstructor
public class AutoOutboundTaskController {
    private final AutoOutboundTaskService service;
    private final AutoOutboundSchedulerService schedulerService;
    private final AutoOutboundDialerService dialerService;

    @GetMapping
    @SaCheckPermission("callcenter:auto-outbound-task:list")
    public R<List<AutoOutboundTaskResponse>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:auto-outbound-task:query")
    public R<AutoOutboundTaskResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:auto-outbound-task:create")
    public R<Long> create(@Valid @RequestBody AutoOutboundTaskRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:auto-outbound-task:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AutoOutboundTaskRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:auto-outbound-task:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/start")
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<Void> start(@PathVariable Long id) {
        service.start(id);
        return R.ok();
    }

    @PostMapping("/{id}/pause")
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<Void> pause(@PathVariable Long id) {
        service.pause(id);
        return R.ok();
    }

    @PostMapping("/{id}/resume")
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<Void> resume(@PathVariable Long id) {
        service.resume(id);
        return R.ok();
    }

    @PostMapping("/{id}/stop")
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<Void> stop(@PathVariable Long id) {
        service.stop(id);
        return R.ok();
    }

    @PostMapping("/{id}/rerun")
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<Void> rerun(@PathVariable Long id) {
        service.rerun(id);
        return R.ok();
    }

    @GetMapping("/{id}/sources")
    @SaCheckPermission("callcenter:auto-outbound-task:query")
    public R<List<AutoOutboundSourceResponse>> sources(@PathVariable Long id) {
        return R.ok(service.listSources(id));
    }

    @PostMapping("/{id}/sources")
    @SaCheckPermission("callcenter:auto-outbound-task:update")
    public R<Long> addSource(@PathVariable Long id, @Valid @RequestBody AutoOutboundSourceRequest request) {
        return R.ok(service.addSource(id, request));
    }

    @DeleteMapping("/{id}/sources/{sourceId}")
    @SaCheckPermission("callcenter:auto-outbound-task:update")
    public R<Void> deleteSource(@PathVariable Long id, @PathVariable Long sourceId) {
        service.deleteSource(id, sourceId);
        return R.ok();
    }

    @PostMapping("/{id}/members/materialize")
    @SaCheckPermission("callcenter:auto-outbound-task:update")
    public R<AutoOutboundMaterializeResponse> materialize(@PathVariable Long id) {
        return R.ok(service.materialize(id));
    }

    @GetMapping("/{id}/members")
    @SaCheckPermission("callcenter:auto-outbound-task:query")
    public TableDataInfo<AutoOutboundMemberResponse> members(
        @PathVariable Long id,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String phoneNumber,
        PageQuery pageQuery
    ) {
        return service.pageMembers(id, status, phoneNumber, pageQuery);
    }

    @GetMapping("/{id}/monitor")
    @SaCheckPermission("callcenter:auto-outbound-task:query")
    public R<AutoOutboundMonitorResponse> monitor(@PathVariable Long id) {
        return R.ok(service.monitor(id));
    }

    @PostMapping("/scheduler/run")
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<AutoOutboundSchedulerResult> runScheduler() {
        return R.ok(schedulerService.execute());
    }

    @PostMapping("/dialer/run")
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<AutoOutboundDialerResult> runDialer() {
        return R.ok(dialerService.execute());
    }
}
