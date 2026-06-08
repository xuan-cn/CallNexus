package org.dromara.resource.gateway.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.gateway.domain.request.CreateFreeSwitchGatewayRequest;
import org.dromara.resource.gateway.domain.request.FreeSwitchGatewayPageQuery;
import org.dromara.resource.gateway.domain.request.UpdateFreeSwitchGatewayRequest;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayResponse;
import org.dromara.resource.gateway.service.FreeSwitchGatewayApplicationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/freeswitch-gateways")
@RequiredArgsConstructor
public class FreeSwitchGatewayController {
    private final FreeSwitchGatewayApplicationService applicationService;

    @GetMapping
    @SaCheckPermission("callcenter:freeswitch-gateway:list")
    public TableDataInfo<FreeSwitchGatewayResponse> page(FreeSwitchGatewayPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:freeswitch-gateway:query")
    public R<FreeSwitchGatewayResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:freeswitch-gateway:create")
    public R<Long> create(@Valid @RequestBody CreateFreeSwitchGatewayRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:freeswitch-gateway:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateFreeSwitchGatewayRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:freeswitch-gateway:delete")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }
}
