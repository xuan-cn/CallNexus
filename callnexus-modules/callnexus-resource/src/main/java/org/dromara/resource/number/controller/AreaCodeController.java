package org.dromara.resource.number.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.number.domain.request.AreaCodePageQuery;
import org.dromara.resource.number.domain.request.AreaCodeRequest;
import org.dromara.resource.number.domain.response.AreaCodeResponse;
import org.dromara.resource.number.service.AreaCodeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/area-codes")
@RequiredArgsConstructor
public class AreaCodeController {

    private final AreaCodeService areaCodeService;

    @GetMapping
    @SaCheckPermission("callcenter:area-code:list")
    public TableDataInfo<AreaCodeResponse> page(AreaCodePageQuery query, PageQuery pageQuery) {
        return areaCodeService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:area-code:query")
    public R<AreaCodeResponse> get(@PathVariable Long id) {
        return R.ok(areaCodeService.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:area-code:create")
    public R<Long> create(@Valid @RequestBody AreaCodeRequest request) {
        return R.ok(areaCodeService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:area-code:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AreaCodeRequest request) {
        areaCodeService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:area-code:delete")
    public R<Void> delete(@PathVariable Long id) {
        areaCodeService.delete(id);
        return R.ok();
    }
}
