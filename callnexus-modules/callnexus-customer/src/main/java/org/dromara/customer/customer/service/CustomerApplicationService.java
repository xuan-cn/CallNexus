package org.dromara.customer.customer.service;

import org.dromara.customer.customer.domain.request.CreateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerAssignmentRequest;
import org.dromara.customer.customer.domain.request.CustomerImportData;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;
import org.dromara.customer.customer.domain.request.UpdateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerPhoneRequest;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.domain.response.CustomerFollowUpResponse;
import org.dromara.customer.customer.domain.response.CustomerPhoneResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import java.util.List;

public interface CustomerApplicationService {
    TableDataInfo<CustomerResponse> page(CustomerPageQuery query, PageQuery pageQuery);
    CustomerResponse get(Long id);
    CustomerResponse getByPhone(String primaryPhone);
    Long create(CreateCustomerRequest request);
    void assign(CustomerAssignmentRequest request);
    Long importCustomer(CustomerImportData data);
    void update(Long id, UpdateCustomerRequest request);
    void claimCurrentAgent(Long id, String businessCallId);
    List<CustomerPhoneResponse> listPhones(Long customerId);
    Long addPhone(Long customerId, CustomerPhoneRequest request);
    void updatePhone(Long customerId, Long phoneId, CustomerPhoneRequest request);
    void setPrimaryPhone(Long customerId, Long phoneId);
    void deletePhone(Long customerId, Long phoneId);
    List<CustomerFollowUpResponse> listFollowUps(Long customerId);
    TableDataInfo<CustomerFollowUpResponse> pageFollowUps(Long customerId, PageQuery pageQuery);
    Long addFollowUp(Long customerId, String content);
    void recordOutboundResult(Long customerId, Long attemptId, String content, String tag);
}
