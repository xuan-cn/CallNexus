package org.dromara.resource.inbound.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.inbound.domain.request.CreateInboundDidEntryRequest;
import org.dromara.resource.inbound.domain.request.InboundDidEntryPageQuery;
import org.dromara.resource.inbound.domain.request.InboundRouteTestRequest;
import org.dromara.resource.inbound.domain.request.UpdateInboundDidEntryRequest;
import org.dromara.resource.inbound.domain.response.InboundDidEntryResponse;
import org.dromara.resource.inbound.domain.response.InboundRouteMatchResponse;
import org.dromara.resource.inbound.service.InboundDidEntryApplicationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inbound-did-entries")
@RequiredArgsConstructor
public class InboundDidEntryController {
    private final InboundDidEntryApplicationService applicationService;

    @GetMapping
    @SaCheckPermission("callcenter:inbound-did:list")
    public TableDataInfo<InboundDidEntryResponse> page(InboundDidEntryPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:inbound-did:query")
    public R<InboundDidEntryResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:inbound-did:create")
    public R<Long> create(@Valid @RequestBody CreateInboundDidEntryRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:inbound-did:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateInboundDidEntryRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:inbound-did:delete")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }

    @PostMapping("/route-test")
    @SaCheckPermission("callcenter:inbound-did:query")
    public R<InboundRouteMatchResponse> test(@Valid @RequestBody InboundRouteTestRequest request) {
        return R.ok(applicationService.test(request));
    }
}
