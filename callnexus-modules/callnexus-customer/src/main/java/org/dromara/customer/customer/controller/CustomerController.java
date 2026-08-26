package org.dromara.customer.customer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.customer.domain.request.CreateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerAssignmentRequest;
import org.dromara.customer.customer.domain.request.ClaimCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;
import org.dromara.customer.customer.domain.request.AddCustomerFollowUpRequest;
import org.dromara.customer.customer.domain.request.UpdateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerPhoneRequest;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.domain.response.CustomerFollowUpResponse;
import org.dromara.customer.customer.domain.response.CustomerPhoneResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    @SaCheckPermission("callcenter:customer:create")
    public R<Long> create(@Valid @RequestBody CreateCustomerRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PostMapping("/assignments")
    @SaCheckPermission("callcenter:customer:assign")
    public R<Void> assign(@Valid @RequestBody CustomerAssignmentRequest request) {
        applicationService.assign(request);
        return R.ok();
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateCustomerRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/claim-current-agent")
    public R<Void> claimCurrentAgent(@PathVariable Long id, @Valid @RequestBody ClaimCustomerRequest request) {
        applicationService.claimCurrentAgent(id, request.getBusinessCallId());
        return R.ok();
    }

    @GetMapping("/{id}/phones")
    public R<List<CustomerPhoneResponse>> listPhones(@PathVariable Long id) {
        return R.ok(applicationService.listPhones(id));
    }

    @PostMapping("/{id}/phones")
    public R<Long> addPhone(@PathVariable Long id, @Valid @RequestBody CustomerPhoneRequest request) {
        return R.ok(applicationService.addPhone(id, request));
    }

    @PutMapping("/{id}/phones/{phoneId}")
    public R<Void> updatePhone(
        @PathVariable Long id,
        @PathVariable Long phoneId,
        @Valid @RequestBody CustomerPhoneRequest request
    ) {
        applicationService.updatePhone(id, phoneId, request);
        return R.ok();
    }

    @PutMapping("/{id}/phones/{phoneId}/primary")
    public R<Void> setPrimaryPhone(@PathVariable Long id, @PathVariable Long phoneId) {
        applicationService.setPrimaryPhone(id, phoneId);
        return R.ok();
    }

    @DeleteMapping("/{id}/phones/{phoneId}")
    public R<Void> deletePhone(@PathVariable Long id, @PathVariable Long phoneId) {
        applicationService.deletePhone(id, phoneId);
        return R.ok();
    }

    @GetMapping("/{id}/follow-ups")
    public R<List<CustomerFollowUpResponse>> listFollowUps(@PathVariable Long id) {
        return R.ok(applicationService.listFollowUps(id));
    }

    @GetMapping("/{id}/follow-ups/page")
    public TableDataInfo<CustomerFollowUpResponse> pageFollowUps(@PathVariable Long id, PageQuery pageQuery) {
        return applicationService.pageFollowUps(id, pageQuery);
    }

    @PostMapping("/{id}/follow-ups")
    public R<Long> addFollowUp(@PathVariable Long id, @Valid @RequestBody AddCustomerFollowUpRequest request) {
        return R.ok(applicationService.addFollowUp(id, request.getContent()));
    }
}
