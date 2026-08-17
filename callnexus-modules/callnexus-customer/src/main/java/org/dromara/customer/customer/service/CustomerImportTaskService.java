package org.dromara.customer.customer.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.customer.domain.request.CustomerImportTaskQuery;
import org.dromara.customer.customer.domain.request.CustomerImportTaskRequest;
import org.dromara.customer.customer.domain.response.CustomerImportTaskResponse;

public interface CustomerImportTaskService {
    TableDataInfo<CustomerImportTaskResponse> page(CustomerImportTaskQuery query, PageQuery pageQuery);
    CustomerImportTaskResponse get(Long taskId);
    Long create(CustomerImportTaskRequest request);
    void update(Long taskId, CustomerImportTaskRequest request);
    void updateStatus(Long taskId, String status);
    void delete(Long taskId);
    void validateCustomers(Long taskId, java.util.List<Long> customerIds);
}
