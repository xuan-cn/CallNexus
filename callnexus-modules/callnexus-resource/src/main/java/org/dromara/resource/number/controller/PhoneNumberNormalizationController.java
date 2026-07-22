package org.dromara.resource.number.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.number.domain.request.PhoneNumberNormalizeRequest;
import org.dromara.resource.number.domain.response.PhoneNumberNormalizeResponse;
import org.dromara.resource.number.service.PhoneNumberNormalizationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/phone-number-normalization")
@RequiredArgsConstructor
public class PhoneNumberNormalizationController {

    private final PhoneNumberNormalizationService service;

    @PostMapping("/test")
    @SaCheckPermission("callcenter:phone-number:query")
    public R<PhoneNumberNormalizeResponse> test(@Valid @RequestBody PhoneNumberNormalizeRequest request) {
        return R.ok(service.normalize(LoginHelper.getTenantId(), request));
    }
}
