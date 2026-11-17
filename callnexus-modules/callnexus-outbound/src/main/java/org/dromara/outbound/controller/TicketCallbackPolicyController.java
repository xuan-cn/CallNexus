package org.dromara.outbound.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.outbound.service.impl.TicketCallbackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SaCheckLogin
@SaCheckPermission("callcenter:auto-outbound-task:update")
@RestController
@RequestMapping("/api/v1/form-templates/{templateId}/callback-policy")
@RequiredArgsConstructor
public class TicketCallbackPolicyController {
    private final TicketCallbackService service;

    @GetMapping
    public R<TicketCallbackService.PolicySettings> get(@PathVariable Long templateId) {
        return R.ok(service.settings(templateId));
    }

    @PutMapping
    public R<Void> update(@PathVariable Long templateId, @Valid @RequestBody TicketCallbackService.PolicyRequest request) {
        service.savePolicy(templateId, request);
        return R.ok();
    }
}
