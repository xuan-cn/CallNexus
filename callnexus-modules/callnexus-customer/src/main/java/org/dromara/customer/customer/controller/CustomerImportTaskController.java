package org.dromara.customer.customer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.customer.domain.request.CustomerAssignmentRequest;
import org.dromara.customer.customer.domain.request.CustomerImportBatchQuery;
import org.dromara.customer.customer.domain.request.CustomerImportRetryRequest;
import org.dromara.customer.customer.domain.request.CustomerImportTaskQuery;
import org.dromara.customer.customer.domain.request.CustomerImportTaskRequest;
import org.dromara.customer.customer.domain.request.CustomerImportTaskStatusRequest;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;
import org.dromara.customer.customer.domain.response.CustomerImportAnalysisResponse;
import org.dromara.customer.customer.domain.response.CustomerImportBatchResponse;
import org.dromara.customer.customer.domain.response.CustomerImportTaskResponse;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.customer.service.CustomerImportService;
import org.dromara.customer.customer.service.CustomerImportTaskService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/customer-import-tasks")
@RequiredArgsConstructor
public class CustomerImportTaskController {
    private final CustomerImportTaskService taskService;
    private final CustomerImportService importService;
    private final CustomerApplicationService customerService;

    @GetMapping
    @SaCheckPermission("callcenter:customer-import-task:list")
    public TableDataInfo<CustomerImportTaskResponse> page(CustomerImportTaskQuery query, PageQuery pageQuery) {
        return taskService.page(query, pageQuery);
    }

    @GetMapping("/{taskId}")
    @SaCheckPermission("callcenter:customer-import-task:list")
    public R<CustomerImportTaskResponse> get(@PathVariable Long taskId) {
        return R.ok(taskService.get(taskId));
    }

    @PostMapping
    @SaCheckPermission("callcenter:customer-import-task:create")
    public R<Long> create(@Valid @RequestBody CustomerImportTaskRequest request) {
        return R.ok(taskService.create(request));
    }

    @PutMapping("/{taskId}")
    @SaCheckPermission("callcenter:customer-import-task:edit")
    public R<Void> update(@PathVariable Long taskId, @Valid @RequestBody CustomerImportTaskRequest request) {
        taskService.update(taskId, request);
        return R.ok();
    }

    @DeleteMapping("/{taskId}")
    @SaCheckPermission("callcenter:customer-import-task:delete")
    public R<Void> delete(@PathVariable Long taskId) {
        taskService.delete(taskId);
        return R.ok();
    }

    @PutMapping("/{taskId}/status")
    @SaCheckPermission("callcenter:customer-import-task:edit")
    public R<Void> updateStatus(@PathVariable Long taskId, @Valid @RequestBody CustomerImportTaskStatusRequest request) {
        taskService.updateStatus(taskId, request.getStatus());
        return R.ok();
    }

    @PostMapping("/template")
    @SaCheckPermission("callcenter:customer-import-task:upload")
    public void template(HttpServletResponse response) {
        importService.downloadTemplate(response);
    }

    @PostMapping(value = "/{taskId}/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission("callcenter:customer-import-task:upload")
    public R<CustomerImportAnalysisResponse> analyze(@PathVariable Long taskId, @RequestPart("file") MultipartFile file) {
        return R.ok(importService.analyze(taskId, file));
    }

    @PostMapping(value = "/{taskId}/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission("callcenter:customer-import-task:upload")
    public R<CustomerImportBatchResponse> upload(@PathVariable Long taskId, @RequestPart("file") MultipartFile file) {
        return R.ok(importService.startImport(taskId, file));
    }

    @GetMapping("/{taskId}/batches")
    @SaCheckPermission("callcenter:customer-import-task:list")
    public TableDataInfo<CustomerImportBatchResponse> batches(
        @PathVariable Long taskId,
        CustomerImportBatchQuery query,
        PageQuery pageQuery
    ) {
        return importService.pageBatches(taskId, query, pageQuery);
    }

    @GetMapping("/{taskId}/batches/{batchId}/rows")
    @SaCheckPermission("callcenter:customer-import-task:list")
    public TableDataInfo<CustomerImportBatchResponse.Row> rows(
        @PathVariable Long taskId,
        @PathVariable Long batchId,
        @RequestParam(required = false) String status,
        PageQuery pageQuery
    ) {
        return importService.pageRows(taskId, batchId, status, pageQuery);
    }

    @PostMapping("/{taskId}/batches/{batchId}/retry")
    @SaCheckPermission("callcenter:customer-import-task:retry")
    public R<CustomerImportBatchResponse> retry(
        @PathVariable Long taskId,
        @PathVariable Long batchId,
        @RequestBody(required = false) CustomerImportRetryRequest request
    ) {
        return R.ok(importService.retryRows(taskId, batchId, request));
    }

    @GetMapping("/{taskId}/batches/{batchId}/errors")
    @SaCheckPermission("callcenter:customer-import-task:list")
    public void errors(@PathVariable Long taskId, @PathVariable Long batchId, HttpServletResponse response) {
        importService.downloadErrors(taskId, batchId, response);
    }

    @GetMapping("/{taskId}/customers")
    @SaCheckPermission("callcenter:customer-assignment:list")
    public TableDataInfo<CustomerResponse> customers(
        @PathVariable Long taskId,
        CustomerPageQuery query,
        PageQuery pageQuery
    ) {
        query.setImportTaskId(taskId);
        return customerService.page(query, pageQuery);
    }

    @PostMapping("/{taskId}/assignments")
    @SaCheckPermission("callcenter:customer-assignment:assign")
    public R<Void> assign(@PathVariable Long taskId, @Valid @RequestBody CustomerAssignmentRequest request) {
        if (Boolean.TRUE.equals(request.getSelectAll())) {
            CustomerPageQuery selectionQuery = request.getSelectionQuery() == null
                ? new CustomerPageQuery() : request.getSelectionQuery();
            selectionQuery.setImportTaskId(taskId);
            List<Long> customerIds = new ArrayList<>();
            int pageNum = 1;
            TableDataInfo<CustomerResponse> page;
            do {
                page = customerService.page(selectionQuery, new PageQuery(1000, pageNum++));
                customerIds.addAll(page.getRows().stream().map(CustomerResponse::getId).toList());
            } while (customerIds.size() < page.getTotal());
            request.setCustomerIds(customerIds);
        }
        taskService.validateCustomers(taskId, request.getCustomerIds());
        customerService.assign(request);
        return R.ok();
    }
}
