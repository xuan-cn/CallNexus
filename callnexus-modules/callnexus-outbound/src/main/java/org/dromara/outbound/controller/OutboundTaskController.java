package org.dromara.outbound.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.outbound.domain.request.AddOutboundMembersRequest;
import org.dromara.outbound.domain.request.CompleteOutboundMemberRequest;
import org.dromara.outbound.domain.request.OutboundTaskRequest;
import org.dromara.outbound.domain.response.OutboundMemberResponse;
import org.dromara.outbound.domain.response.OutboundTaskStatisticsResponse;
import org.dromara.outbound.domain.response.OutboundTaskResponse;
import org.dromara.outbound.service.OutboundTaskService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/outbound-tasks")
@RequiredArgsConstructor
public class OutboundTaskController {
    private final OutboundTaskService service;

    @GetMapping
    @SaCheckPermission("callcenter:outbound-task:list")
    public R<List<OutboundTaskResponse>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:outbound-task:query")
    public R<OutboundTaskResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:outbound-task:create")
    public R<Long> create(@Valid @RequestBody OutboundTaskRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:outbound-task:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody OutboundTaskRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:outbound-task:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/start")
    @SaCheckPermission("callcenter:outbound-task:update")
    public R<Void> start(@PathVariable Long id) {
        service.start(id);
        return R.ok();
    }

    @PostMapping("/{id}/pause")
    @SaCheckPermission("callcenter:outbound-task:update")
    public R<Void> pause(@PathVariable Long id) {
        service.pause(id);
        return R.ok();
    }

    @PostMapping("/{id}/members")
    @SaCheckPermission("callcenter:outbound-task:update")
    public R<Void> addCustomers(@PathVariable Long id, @Valid @RequestBody AddOutboundMembersRequest request) {
        service.addCustomers(id, request.getCustomerIds());
        return R.ok();
    }

    @GetMapping("/{id}/members")
    @SaCheckPermission("callcenter:outbound-task:query")
    public R<List<OutboundMemberResponse>> listMembers(@PathVariable Long id) {
        return R.ok(service.listMembers(id));
    }

    @GetMapping("/{id}/statistics")
    @SaCheckPermission("callcenter:outbound-task:query")
    public R<OutboundTaskStatisticsResponse> statistics(@PathVariable Long id) {
        return R.ok(service.statistics(id));
    }

    @PostMapping("/{id}/recover-expired")
    @SaCheckPermission("callcenter:outbound-task:update")
    public R<Integer> recoverExpired(@PathVariable Long id) {
        return R.ok(service.recoverExpired(id));
    }

    @PostMapping("/{id}/claim-next")
    @SaCheckPermission("callcenter:outbound-task:execute")
    public R<OutboundMemberResponse> claimNext(@PathVariable Long id) {
        return R.ok(service.claimNext(id));
    }

    @PostMapping("/members/{memberId}/renew-lease")
    @SaCheckPermission("callcenter:outbound-task:execute")
    public R<OutboundMemberResponse> renewLease(@PathVariable Long memberId) {
        return R.ok(service.renewLease(memberId));
    }

    @PostMapping("/members/{memberId}/dial")
    @SaCheckPermission("callcenter:outbound-task:execute")
    public R<OutboundMemberResponse> dial(@PathVariable Long memberId) {
        return R.ok(service.dial(memberId));
    }

    @PostMapping("/members/{memberId}/complete")
    @SaCheckPermission("callcenter:outbound-task:execute")
    public R<Void> complete(@PathVariable Long memberId, @Valid @RequestBody CompleteOutboundMemberRequest request) {
        service.complete(memberId, request);
        return R.ok();
    }
}
