package org.dromara.customer.customer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.customer.domain.request.CreateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;
import org.dromara.customer.customer.domain.request.AddCustomerFollowUpRequest;
import org.dromara.customer.customer.domain.request.UpdateCustomerRequest;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.domain.response.CustomerFollowUpResponse;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerApplicationService applicationService;

    @GetMapping
    public TableDataInfo<CustomerResponse> page(CustomerPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    public R<CustomerResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @GetMapping("/by-phone")
    public R<CustomerResponse> getByPhone(@RequestParam String primaryPhone) {
        return R.ok(applicationService.getByPhone(primaryPhone));
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody CreateCustomerRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateCustomerRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @GetMapping("/{id}/follow-ups")
    public R<List<CustomerFollowUpResponse>> listFollowUps(@PathVariable Long id) {
        return R.ok(applicationService.listFollowUps(id));
    }

    @PostMapping("/{id}/follow-ups")
    public R<Long> addFollowUp(@PathVariable Long id, @Valid @RequestBody AddCustomerFollowUpRequest request) {
        return R.ok(applicationService.addFollowUp(id, request.getContent()));
    }
}
