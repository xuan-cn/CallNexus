package org.dromara.customer.customer.service.impl;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.customer.domain.Customer;
import org.dromara.customer.customer.domain.CustomerFollowUp;
import org.dromara.customer.customer.domain.request.CreateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;
import org.dromara.customer.customer.domain.request.UpdateCustomerRequest;
import org.dromara.customer.customer.domain.response.CustomerFollowUpResponse;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.mapper.CustomerFollowUpMapper;
import org.dromara.customer.customer.mapper.CustomerMapper;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.service.DynamicFormSubmissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.call.service.CallBusinessAssociationService;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomerApplicationServiceImpl implements CustomerApplicationService {
    private final CustomerMapper customerMapper;
    private final CustomerFollowUpMapper followUpMapper;
    private final DynamicFormSubmissionService formSubmissionService;
    private final CallBusinessAssociationService callBusinessAssociationService;

    @Override
    public TableDataInfo<CustomerResponse> page(CustomerPageQuery query, PageQuery pageQuery) {
        Page<Customer> page = customerMapper.selectPage(pageQuery.build(), new LambdaQueryWrapper<Customer>()
            .like(query.getPrimaryPhone() != null && !query.getPrimaryPhone().isBlank(), Customer::getPrimaryPhone, query.getPrimaryPhone())
            .like(query.getCustomerName() != null && !query.getCustomerName().isBlank(), Customer::getCustomerName, query.getCustomerName())
            .orderByDesc(Customer::getCreateTime));
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public CustomerResponse get(Long id) {
        Customer customer = customerMapper.selectById(id);
        if (customer == null) throw new ServiceException("CUSTOMER_NOT_FOUND");
        return toDetailResponse(customer);
    }

    @Override
    public CustomerResponse getByPhone(String primaryPhone) {
        if (primaryPhone == null || primaryPhone.isBlank()) return null;
        Customer customer = findByPhone(primaryPhone);
        return customer == null ? null : toDetailResponse(customer);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateCustomerRequest request) {
        String primaryPhone = request.getPrimaryPhone().trim();
        Customer existingCustomer = findByPhone(primaryPhone);
        if (existingCustomer != null) {
            callBusinessAssociationService.associateCustomer(request.getSourceCallId(), existingCustomer.getId());
            return existingCustomer.getId();
        }
        Customer customer = new Customer();
        customer.setPrimaryPhone(primaryPhone);
        customer.setCustomerName(request.getCustomerName());
        customer.setTemplateId(request.getTemplateId());
        customer.setSourceCallId(request.getSourceCallId());
        customerMapper.insert(customer);
        formSubmissionService.validateAndSave(request.getTemplateId(), FormBusinessType.CUSTOMER, customer.getId(), request.getFormData());
        callBusinessAssociationService.associateCustomer(request.getSourceCallId(), customer.getId());
        return customer.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UpdateCustomerRequest request) {
        Customer customer = requireCustomer(id);
        customer.setCustomerName(request.getCustomerName());
        customer.setTemplateId(request.getTemplateId());
        customerMapper.updateById(customer);
        formSubmissionService.validateAndSave(request.getTemplateId(), FormBusinessType.CUSTOMER, id, request.getFormData());
    }

    @Override
    public List<CustomerFollowUpResponse> listFollowUps(Long customerId) {
        requireCustomer(customerId);
        return followUpMapper.selectList(new LambdaQueryWrapper<CustomerFollowUp>()
                .eq(CustomerFollowUp::getCustomerId, customerId)
                .orderByDesc(CustomerFollowUp::getCreateTime))
            .stream().map(this::toFollowUpResponse).toList();
    }

    @Override
    public Long addFollowUp(Long customerId, String content) {
        requireCustomer(customerId);
        CustomerFollowUp followUp = new CustomerFollowUp();
        followUp.setCustomerId(customerId);
        followUp.setContent(content.trim());
        followUp.setFollowUpByName(LoginHelper.getUsername());
        followUpMapper.insert(followUp);
        return followUp.getId();
    }

    private Customer findByPhone(String primaryPhone) {
        return customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
            .eq(Customer::getPrimaryPhone, primaryPhone.trim())
            .last("LIMIT 1"));
    }

    private Customer requireCustomer(Long id) {
        Customer customer = customerMapper.selectById(id);
        if (customer == null) throw new ServiceException("CUSTOMER_NOT_FOUND");
        return customer;
    }

    private CustomerFollowUpResponse toFollowUpResponse(CustomerFollowUp followUp) {
        CustomerFollowUpResponse response = new CustomerFollowUpResponse();
        response.setId(followUp.getId());
        response.setContent(followUp.getContent());
        response.setFollowUpBy(followUp.getCreateBy());
        response.setFollowUpByName(followUp.getFollowUpByName());
        response.setFollowUpTime(followUp.getCreateTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        return response;
    }

    private CustomerResponse toDetailResponse(Customer customer) {
        CustomerResponse response = toResponse(customer);
        response.setFormData(customer.getTemplateId() == null
            ? Map.of()
            : formSubmissionService.getFormData(FormBusinessType.CUSTOMER, customer.getId()));
        return response;
    }

    private CustomerResponse toResponse(Customer customer) {
        CustomerResponse response = new CustomerResponse();
        response.setId(customer.getId());
        response.setPrimaryPhone(customer.getPrimaryPhone());
        response.setCustomerName(customer.getCustomerName());
        response.setTemplateId(customer.getTemplateId());
        response.setSourceCallId(customer.getSourceCallId());
        response.setCreateTime(customer.getCreateTime().toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime());
        return response;
    }
}
