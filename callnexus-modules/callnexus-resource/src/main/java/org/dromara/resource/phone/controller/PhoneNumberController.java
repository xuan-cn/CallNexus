package org.dromara.resource.phone.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.phone.domain.request.CreatePhoneNumberRequest;
import org.dromara.resource.phone.domain.request.PhoneNumberPageQuery;
import org.dromara.resource.phone.domain.request.UpdatePhoneNumberRequest;
import org.dromara.resource.phone.domain.response.PhoneNumberResponse;
import org.dromara.resource.phone.service.PhoneNumberApplicationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/phone-numbers")
@RequiredArgsConstructor
public class PhoneNumberController {
    private final PhoneNumberApplicationService applicationService;

    @GetMapping
    @SaCheckPermission("callcenter:phone-number:list")
    public TableDataInfo<PhoneNumberResponse> page(PhoneNumberPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:phone-number:query")
    public R<PhoneNumberResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:phone-number:create")
    public R<Long> create(@Valid @RequestBody CreatePhoneNumberRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:phone-number:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdatePhoneNumberRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:phone-number:delete")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }
}
