package org.dromara.resource.number.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.number.domain.request.MobileNumberSegmentPageQuery;
import org.dromara.resource.number.domain.request.MobileNumberSegmentRequest;
import org.dromara.resource.number.domain.response.MobileNumberSegmentResponse;
import org.dromara.resource.number.service.MobileNumberSegmentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mobile-number-segments")
@RequiredArgsConstructor
public class MobileNumberSegmentController {

    private final MobileNumberSegmentService service;

    @GetMapping
    @SaCheckPermission("callcenter:mobile-segment:list")
    public TableDataInfo<MobileNumberSegmentResponse> page(MobileNumberSegmentPageQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:mobile-segment:query")
    public R<MobileNumberSegmentResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:mobile-segment:create")
    public R<Long> create(@Valid @RequestBody MobileNumberSegmentRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:mobile-segment:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody MobileNumberSegmentRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:mobile-segment:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
