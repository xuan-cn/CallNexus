package org.dromara.customer.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.customer.ticket.domain.request.TicketUrgencyLevelRequest;
import org.dromara.customer.ticket.domain.response.TicketUrgencyLevelResponse;
import org.dromara.customer.ticket.service.TicketUrgencyLevelService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/ticket-urgency-levels")
@RequiredArgsConstructor
public class TicketUrgencyLevelController {
    private final TicketUrgencyLevelService service;

    @GetMapping
    public R<List<TicketUrgencyLevelResponse>> list(
        @RequestParam(defaultValue = "false") boolean includeDisabled) {
        if (includeDisabled) StpUtil.checkPermission("callcenter:ticket-urgency:manage");
        return R.ok(service.list(includeDisabled));
    }

    @PostMapping
    @SaCheckPermission("callcenter:ticket-urgency:manage")
    public R<Long> create(@Valid @RequestBody TicketUrgencyLevelRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ticket-urgency:manage")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody TicketUrgencyLevelRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:ticket-urgency:manage")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
