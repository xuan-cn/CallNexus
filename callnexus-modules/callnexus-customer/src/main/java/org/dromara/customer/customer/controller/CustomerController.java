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
import org.dromara.customer.form.service.BusinessDataExportService;
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
    private static final int MAX_EXPORT_ROWS = 5000;
    private final CustomerApplicationService applicationService;
    private final BusinessDataExportService exportService;

    @GetMapping
    public TableDataInfo<CustomerResponse> page(CustomerPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @PostMapping("/search")
    public TableDataInfo<CustomerResponse> search(@RequestBody CustomerPageQuery query) {
        return applicationService.page(query, new PageQuery(normalizePageSize(query.getPageSize()), normalizePageNum(query.getPageNum())));
    }

    @PostMapping("/export")
    public void export(@RequestBody CustomerPageQuery query, jakarta.servlet.http.HttpServletResponse response) {
        TableDataInfo<CustomerResponse> data = applicationService.page(query, new PageQuery(MAX_EXPORT_ROWS, 1));
        if (data.getTotal() > MAX_EXPORT_ROWS) {
            throw new org.dromara.common.core.exception.ServiceException("单次最多导出 " + MAX_EXPORT_ROWS + " 条客户资料，请缩小查询范围");
        }
        exportService.exportCustomers(data.getRows(), query.getTemplateId(), response);
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

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:customer:delete")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
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
