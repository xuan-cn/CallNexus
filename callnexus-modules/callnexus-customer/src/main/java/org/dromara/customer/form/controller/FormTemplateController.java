package org.dromara.customer.form.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.request.SaveFormTemplateRequest;
import org.dromara.customer.form.domain.response.FormTemplateResponse;
import org.dromara.customer.form.service.FormTemplateApplicationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/form-templates")
@RequiredArgsConstructor
public class FormTemplateController {
    private final FormTemplateApplicationService applicationService;

    @GetMapping
    public R<List<FormTemplateResponse>> list(@RequestParam(required = false) FormBusinessType businessType) {
        return R.ok(applicationService.list(businessType));
    }

    @GetMapping("/{id}")
    public R<FormTemplateResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody SaveFormTemplateRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody SaveFormTemplateRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }
}
