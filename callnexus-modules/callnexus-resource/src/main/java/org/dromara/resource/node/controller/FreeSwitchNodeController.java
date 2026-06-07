package org.dromara.resource.node.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.node.domain.request.CreateFreeSwitchNodeRequest;
import org.dromara.resource.node.domain.request.FreeSwitchNodePageQuery;
import org.dromara.resource.node.domain.request.UpdateFreeSwitchNodeRequest;
import org.dromara.resource.node.domain.response.FreeSwitchNodeResponse;
import org.dromara.resource.node.service.FreeSwitchNodeApplicationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/freeswitch-nodes")
@RequiredArgsConstructor
public class FreeSwitchNodeController {
    private final FreeSwitchNodeApplicationService applicationService;

    @GetMapping
    @SaCheckPermission("callcenter:freeswitch-node:list")
    public TableDataInfo<FreeSwitchNodeResponse> page(FreeSwitchNodePageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:freeswitch-node:query")
    public R<FreeSwitchNodeResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:freeswitch-node:create")
    public R<Long> create(@Valid @RequestBody CreateFreeSwitchNodeRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:freeswitch-node:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateFreeSwitchNodeRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:freeswitch-node:delete")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }
}
