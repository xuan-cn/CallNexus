package org.dromara.resource.sip.security.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.sip.security.domain.SipRegistrationBlock;
import org.dromara.resource.sip.security.domain.SipRegistrationSecurityEvent;
import org.dromara.resource.sip.security.domain.SipRegistrationSecurityPolicy;
import org.dromara.resource.sip.security.domain.request.SipRegistrationLockRequest;
import org.dromara.resource.sip.security.domain.request.SipSecurityBlockPageQuery;
import org.dromara.resource.sip.security.domain.request.SipSecurityEventPageQuery;
import org.dromara.resource.sip.security.domain.request.SipSecurityPolicySaveRequest;
import org.dromara.resource.sip.security.service.SipRegistrationSecurityManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sip-registration-security")
@RequiredArgsConstructor
public class SipRegistrationSecurityController {
    private final SipRegistrationSecurityManagementService service;

    @GetMapping("/events")
    @SaCheckPermission("callcenter:sip-security:list")
    public TableDataInfo<SipRegistrationSecurityEvent> events(SipSecurityEventPageQuery query, PageQuery pageQuery) {
        return service.pageEvents(query, pageQuery);
    }

    @GetMapping("/blocks")
    @SaCheckPermission("callcenter:sip-security:list")
    public TableDataInfo<SipRegistrationBlock> blocks(SipSecurityBlockPageQuery query, PageQuery pageQuery) {
        return service.pageBlocks(query, pageQuery);
    }

    @PostMapping("/blocks/{id}/release")
    @SaCheckPermission("callcenter:sip-security:unblock")
    public R<Void> release(@PathVariable Long id, @RequestBody(required = false) SipRegistrationLockRequest request) {
        service.releaseBlock(id, request == null ? null : request.getReason());
        return R.ok();
    }

    @GetMapping("/policies")
    @SaCheckPermission("callcenter:sip-security:policy")
    public R<List<SipRegistrationSecurityPolicy>> policies() {
        return R.ok(service.listPolicies());
    }

    @GetMapping("/policies/effective")
    @SaCheckPermission("callcenter:sip-security:policy")
    public R<SipRegistrationSecurityPolicy> effectivePolicy(@RequestParam(defaultValue = "0") Long nodeId) {
        return R.ok(service.effectivePolicy(nodeId));
    }

    @PostMapping("/policies")
    @SaCheckPermission("callcenter:sip-security:policy")
    public R<Long> savePolicy(@Valid @RequestBody SipSecurityPolicySaveRequest request) {
        return R.ok(service.savePolicy(request));
    }
}
