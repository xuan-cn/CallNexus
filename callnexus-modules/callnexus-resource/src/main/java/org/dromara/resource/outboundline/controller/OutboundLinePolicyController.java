package org.dromara.resource.outboundline.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.outboundline.domain.request.OutboundLinePolicyPageQuery;
import org.dromara.resource.outboundline.domain.request.OutboundLinePolicyRequest;
import org.dromara.resource.outboundline.domain.response.OutboundLinePolicyResponse;
import org.dromara.resource.outboundline.service.OutboundLinePolicyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/outbound-line-policies")
@RequiredArgsConstructor
public class OutboundLinePolicyController {
    private final OutboundLinePolicyService service;

    @GetMapping
    @SaCheckPermission("callcenter:outbound-line-policy:list")
    public TableDataInfo<OutboundLinePolicyResponse> page(OutboundLinePolicyPageQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:outbound-line-policy:query")
    public R<OutboundLinePolicyResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:outbound-line-policy:create")
    public R<Long> create(@Valid @RequestBody OutboundLinePolicyRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:outbound-line-policy:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody OutboundLinePolicyRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:outbound-line-policy:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
