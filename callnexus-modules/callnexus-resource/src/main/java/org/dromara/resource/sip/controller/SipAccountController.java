package org.dromara.resource.sip.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.sip.domain.request.CreateSipAccountRequest;
import org.dromara.resource.sip.domain.request.SipAccountPageQuery;
import org.dromara.resource.sip.domain.request.UpdateSipAccountRequest;
import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.service.SipAccountApplicationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sip-accounts")
@RequiredArgsConstructor
public class SipAccountController {
    private final SipAccountApplicationService applicationService;

    @GetMapping
    @SaCheckPermission("callcenter:sip-account:list")
    public TableDataInfo<SipAccountResponse> page(SipAccountPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:sip-account:query")
    public R<SipAccountResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:sip-account:create")
    public R<Long> create(@Valid @RequestBody CreateSipAccountRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:sip-account:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateSipAccountRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:sip-account:delete")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }
}
