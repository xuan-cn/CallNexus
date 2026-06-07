package org.dromara.customer.customer.service;

import org.dromara.customer.customer.domain.request.CreateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;
import org.dromara.customer.customer.domain.request.UpdateCustomerRequest;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.domain.response.CustomerFollowUpResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import java.util.List;

public interface CustomerApplicationService {
    TableDataInfo<CustomerResponse> page(CustomerPageQuery query, PageQuery pageQuery);
    CustomerResponse get(Long id);
    CustomerResponse getByPhone(String primaryPhone);
    Long create(CreateCustomerRequest request);
    void update(Long id, UpdateCustomerRequest request);
    List<CustomerFollowUpResponse> listFollowUps(Long customerId);
    Long addFollowUp(Long customerId, String content);
}
