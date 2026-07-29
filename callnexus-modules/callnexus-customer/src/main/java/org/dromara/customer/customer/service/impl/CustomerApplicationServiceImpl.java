package org.dromara.customer.customer.service.impl;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.customer.domain.Customer;
import org.dromara.customer.customer.domain.CustomerFollowUp;
import org.dromara.customer.customer.domain.CustomerPhone;
import org.dromara.customer.customer.domain.request.CreateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerImportData;
import org.dromara.customer.customer.domain.request.CustomerPhoneRequest;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;
import org.dromara.customer.customer.domain.request.UpdateCustomerRequest;
import org.dromara.customer.customer.domain.response.CustomerFollowUpResponse;
import org.dromara.customer.customer.domain.response.CustomerPhoneResponse;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.mapper.CustomerFollowUpMapper;
import org.dromara.customer.customer.mapper.CustomerMapper;
import org.dromara.customer.customer.mapper.CustomerPhoneMapper;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.customer.service.CustomerPhoneNormalizer;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.service.DynamicFormSubmissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.call.service.CallBusinessAssociationService;
import org.springframework.dao.DuplicateKeyException;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerApplicationServiceImpl implements CustomerApplicationService {
    private final CustomerMapper customerMapper;
    private final CustomerPhoneMapper customerPhoneMapper;
    private final CustomerFollowUpMapper followUpMapper;
    private final CustomerPhoneNormalizer phoneNormalizer;
    private final DynamicFormSubmissionService formSubmissionService;
    private final CallBusinessAssociationService callBusinessAssociationService;

    @Override
    public TableDataInfo<CustomerResponse> page(CustomerPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<Customer>()
            .like(query.getCustomerName() != null && !query.getCustomerName().isBlank(), Customer::getCustomerName, query.getCustomerName())
            .orderByDesc(Customer::getCreateTime);
        if (query.getPrimaryPhone() != null && !query.getPrimaryPhone().isBlank()) {
            String keyword = phoneNormalizer.clean(query.getPrimaryPhone());
            Set<Long> customerIds = customerPhoneMapper.selectList(new LambdaQueryWrapper<CustomerPhone>()
                    .like(CustomerPhone::getNormalizedPhone, keyword))
                .stream().map(CustomerPhone::getCustomerId).collect(Collectors.toSet());
            if (customerIds.isEmpty()) {
                wrapper.like(Customer::getPrimaryPhone, keyword);
            } else {
                wrapper.and(condition -> condition.in(Customer::getId, customerIds)
                    .or().like(Customer::getPrimaryPhone, keyword));
            }
        }
        Page<Customer> page = customerMapper.selectPage(pageQuery.build(), wrapper);
        Map<Long, List<CustomerPhoneResponse>> phonesByCustomer = loadPhones(page.getRecords().stream().map(Customer::getId).toList());
        return new TableDataInfo<>(page.getRecords().stream()
            .map(customer -> toResponse(customer, phonesByCustomer.get(customer.getId())))
            .toList(), page.getTotal());
    }

    @Override
    public CustomerResponse get(Long id) {
        Customer customer = customerMapper.selectById(id);
        if (customer == null) throw new ServiceException("客户不存在");
        return toDetailResponse(customer);
    }

    @Override
    public CustomerResponse getByPhone(String primaryPhone) {
        if (!phoneNormalizer.isValid(primaryPhone)) return null;
        Customer customer = findByPhone(primaryPhone);
        return customer == null ? null : toDetailResponse(customer);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateCustomerRequest request) {
        String primaryPhone = phoneNormalizer.normalize(request.getPrimaryPhone());
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
        CustomerPhone phone = new CustomerPhone();
        phone.setCustomerId(customer.getId());
        phone.setPhoneNumber(primaryPhone);
        phone.setNormalizedPhone(primaryPhone);
        phone.setPhoneType(inferPhoneType(primaryPhone));
        phone.setPhoneLabel("主号码");
        phone.setPrimaryFlag(true);
        phone.setEnabled(true);
        phone.setSortOrder(0);
        customerPhoneMapper.insert(phone);
        formSubmissionService.validateAndSave(request.getTemplateId(), FormBusinessType.CUSTOMER, customer.getId(), request.getFormData());
        callBusinessAssociationService.associateCustomer(request.getSourceCallId(), customer.getId());
        return customer.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long importCustomer(CustomerImportData data) {
        String primaryPhone = phoneNormalizer.normalize(data.getPrimaryPhone());
        ensurePhoneAvailable(primaryPhone);
        Set<String> rowPhones = new java.util.HashSet<>();
        rowPhones.add(primaryPhone);
        for (CustomerImportData.Phone phone : data.getAdditionalPhones()) {
            String normalizedPhone = phoneNormalizer.normalize(phone.getPhoneNumber());
            if (!rowPhones.add(normalizedPhone)) {
                throw new ServiceException("同一客户的电话号码不能重复：" + normalizedPhone);
            }
            ensurePhoneAvailable(normalizedPhone);
            phone.setPhoneNumber(normalizedPhone);
        }

        Customer customer = new Customer();
        customer.setPrimaryPhone(primaryPhone);
        customer.setCustomerName(data.getCustomerName());
        customerMapper.insert(customer);
        try {
            insertImportedPhone(customer.getId(), primaryPhone, "主号码", true, 0);
            int sortOrder = 1;
            for (CustomerImportData.Phone phone : data.getAdditionalPhones()) {
                insertImportedPhone(customer.getId(), phone.getPhoneNumber(), phone.getPhoneLabel(), false, sortOrder++);
            }
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("客户电话号码已被其他客户绑定");
        }
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
        callBusinessAssociationService.associateCustomer(request.getSourceCallId(), id);
    }

    @Override
    public List<CustomerPhoneResponse> listPhones(Long customerId) {
        requireCustomer(customerId);
        return customerPhoneMapper.selectList(phoneQuery(customerId)).stream().map(this::toPhoneResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addPhone(Long customerId, CustomerPhoneRequest request) {
        Customer customer = requireCustomer(customerId);
        String normalizedPhone = phoneNormalizer.normalize(request.getPhoneNumber());
        rejectPhoneOwnedByAnotherCustomer(normalizedPhone, customerId, null);
        long phoneCount = customerPhoneMapper.selectCount(new LambdaQueryWrapper<CustomerPhone>()
            .eq(CustomerPhone::getCustomerId, customerId));
        boolean primary = phoneCount == 0 || Boolean.TRUE.equals(request.getPrimaryFlag());
        if (primary) {
            clearPrimaryPhone(customerId);
        }
        CustomerPhone phone = new CustomerPhone();
        applyPhoneRequest(phone, request, normalizedPhone);
        phone.setCustomerId(customerId);
        phone.setPrimaryFlag(primary);
        if (primary) {
            phone.setEnabled(true);
        }
        try {
            customerPhoneMapper.insert(phone);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("该电话号码已绑定其他客户");
        }
        if (primary) {
            syncPrimaryPhone(customer, normalizedPhone);
        }
        return phone.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePhone(Long customerId, Long phoneId, CustomerPhoneRequest request) {
        Customer customer = requireCustomer(customerId);
        CustomerPhone phone = requirePhone(customerId, phoneId);
        String normalizedPhone = phoneNormalizer.normalize(request.getPhoneNumber());
        rejectPhoneOwnedByAnotherCustomer(normalizedPhone, customerId, phoneId);
        if (Boolean.TRUE.equals(phone.getPrimaryFlag()) && Boolean.FALSE.equals(request.getEnabled())) {
            throw new ServiceException("主号码不能停用，请先将其他号码设为主号码");
        }
        boolean makePrimary = Boolean.TRUE.equals(request.getPrimaryFlag());
        if (makePrimary) {
            clearPrimaryPhone(customerId);
        }
        applyPhoneRequest(phone, request, normalizedPhone);
        phone.setPrimaryFlag(Boolean.TRUE.equals(phone.getPrimaryFlag()) || makePrimary);
        if (makePrimary) {
            phone.setEnabled(true);
        }
        try {
            customerPhoneMapper.updateById(phone);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("该电话号码已绑定其他客户");
        }
        if (Boolean.TRUE.equals(phone.getPrimaryFlag())) {
            syncPrimaryPhone(customer, normalizedPhone);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setPrimaryPhone(Long customerId, Long phoneId) {
        Customer customer = requireCustomer(customerId);
        CustomerPhone phone = requirePhone(customerId, phoneId);
        if (!Boolean.TRUE.equals(phone.getEnabled())) {
            throw new ServiceException("停用号码不能设为主号码");
        }
        clearPrimaryPhone(customerId);
        phone.setPrimaryFlag(true);
        customerPhoneMapper.updateById(phone);
        syncPrimaryPhone(customer, phone.getNormalizedPhone());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePhone(Long customerId, Long phoneId) {
        Customer customer = requireCustomer(customerId);
        CustomerPhone phone = requirePhone(customerId, phoneId);
        List<CustomerPhone> phones = customerPhoneMapper.selectList(phoneQuery(customerId));
        if (phones.size() <= 1) {
            throw new ServiceException("客户至少需要保留一个电话号码");
        }
        customerPhoneMapper.deleteById(phoneId);
        if (!Boolean.TRUE.equals(phone.getPrimaryFlag())) {
            return;
        }
        CustomerPhone replacement = phones.stream()
            .filter(item -> !item.getId().equals(phoneId))
            .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
            .findFirst()
            .orElseGet(() -> phones.stream().filter(item -> !item.getId().equals(phoneId)).findFirst().orElseThrow());
        replacement.setEnabled(true);
        replacement.setPrimaryFlag(true);
        customerPhoneMapper.updateById(replacement);
        syncPrimaryPhone(customer, replacement.getNormalizedPhone());
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
        String normalizedPhone = phoneNormalizer.normalize(primaryPhone);
        CustomerPhone phone = customerPhoneMapper.selectOne(new LambdaQueryWrapper<CustomerPhone>()
            .eq(CustomerPhone::getNormalizedPhone, normalizedPhone)
            .eq(CustomerPhone::getEnabled, true)
            .last("LIMIT 1"));
        if (phone != null) {
            return customerMapper.selectById(phone.getCustomerId());
        }
        return customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
            .eq(Customer::getPrimaryPhone, normalizedPhone)
            .last("LIMIT 1"));
    }

    private void ensurePhoneAvailable(String normalizedPhone) {
        if (findByPhone(normalizedPhone) != null) {
            throw new ServiceException("号码 " + normalizedPhone + " 已绑定其他客户");
        }
    }

    private void insertImportedPhone(
        Long customerId,
        String normalizedPhone,
        String phoneLabel,
        boolean primary,
        int sortOrder
    ) {
        CustomerPhone phone = new CustomerPhone();
        phone.setCustomerId(customerId);
        phone.setPhoneNumber(normalizedPhone);
        phone.setNormalizedPhone(normalizedPhone);
        phone.setPhoneType(inferPhoneType(normalizedPhone));
        phone.setPhoneLabel(phoneLabel);
        phone.setPrimaryFlag(primary);
        phone.setEnabled(true);
        phone.setSortOrder(sortOrder);
        customerPhoneMapper.insert(phone);
    }

    private Customer requireCustomer(Long id) {
        Customer customer = customerMapper.selectById(id);
        if (customer == null) throw new ServiceException("客户不存在");
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
        return toResponse(customer, listPhoneResponses(customer.getId()));
    }

    private CustomerResponse toResponse(Customer customer, List<CustomerPhoneResponse> phones) {
        CustomerResponse response = new CustomerResponse();
        response.setId(customer.getId());
        response.setPrimaryPhone(customer.getPrimaryPhone());
        response.setCustomerName(customer.getCustomerName());
        response.setTemplateId(customer.getTemplateId());
        response.setSourceCallId(customer.getSourceCallId());
        response.setCreateTime(customer.getCreateTime().toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime());
        response.setPhones(phones == null || phones.isEmpty() ? legacyPhone(customer) : phones);
        return response;
    }

    private LambdaQueryWrapper<CustomerPhone> phoneQuery(Long customerId) {
        return new LambdaQueryWrapper<CustomerPhone>()
            .eq(CustomerPhone::getCustomerId, customerId)
            .orderByDesc(CustomerPhone::getPrimaryFlag)
            .orderByAsc(CustomerPhone::getSortOrder)
            .orderByAsc(CustomerPhone::getCreateTime);
    }

    private List<CustomerPhoneResponse> listPhoneResponses(Long customerId) {
        return customerPhoneMapper.selectList(phoneQuery(customerId)).stream().map(this::toPhoneResponse).toList();
    }

    private Map<Long, List<CustomerPhoneResponse>> loadPhones(List<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        return customerPhoneMapper.selectList(new LambdaQueryWrapper<CustomerPhone>()
                .in(CustomerPhone::getCustomerId, customerIds)
                .orderByDesc(CustomerPhone::getPrimaryFlag)
                .orderByAsc(CustomerPhone::getSortOrder)
                .orderByAsc(CustomerPhone::getCreateTime))
            .stream()
            .map(this::toPhoneResponseEntry)
            .collect(Collectors.groupingBy(Map.Entry::getKey,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private Map.Entry<Long, CustomerPhoneResponse> toPhoneResponseEntry(CustomerPhone phone) {
        return Map.entry(phone.getCustomerId(), toPhoneResponse(phone));
    }

    private CustomerPhoneResponse toPhoneResponse(CustomerPhone phone) {
        CustomerPhoneResponse response = new CustomerPhoneResponse();
        response.setId(phone.getId());
        response.setPhoneNumber(phone.getPhoneNumber());
        response.setNormalizedPhone(phone.getNormalizedPhone());
        response.setPhoneType(phone.getPhoneType());
        response.setPhoneLabel(phone.getPhoneLabel());
        response.setPrimaryFlag(phone.getPrimaryFlag());
        response.setEnabled(phone.getEnabled());
        response.setSortOrder(phone.getSortOrder());
        return response;
    }

    private List<CustomerPhoneResponse> legacyPhone(Customer customer) {
        CustomerPhoneResponse response = new CustomerPhoneResponse();
        response.setPhoneNumber(customer.getPrimaryPhone());
        response.setNormalizedPhone(customer.getPrimaryPhone());
        response.setPhoneType(inferPhoneType(customer.getPrimaryPhone()));
        response.setPhoneLabel("主号码");
        response.setPrimaryFlag(true);
        response.setEnabled(true);
        response.setSortOrder(0);
        return List.of(response);
    }

    private CustomerPhone requirePhone(Long customerId, Long phoneId) {
        CustomerPhone phone = customerPhoneMapper.selectOne(new LambdaQueryWrapper<CustomerPhone>()
            .eq(CustomerPhone::getId, phoneId)
            .eq(CustomerPhone::getCustomerId, customerId)
            .last("LIMIT 1"));
        if (phone == null) {
            throw new ServiceException("客户电话号码不存在");
        }
        return phone;
    }

    private void rejectPhoneOwnedByAnotherCustomer(String normalizedPhone, Long customerId, Long phoneId) {
        LambdaQueryWrapper<CustomerPhone> wrapper = new LambdaQueryWrapper<CustomerPhone>()
            .eq(CustomerPhone::getNormalizedPhone, normalizedPhone)
            .ne(CustomerPhone::getCustomerId, customerId);
        if (phoneId != null) {
            wrapper.ne(CustomerPhone::getId, phoneId);
        }
        if (customerPhoneMapper.selectCount(wrapper) > 0) {
            throw new ServiceException("该电话号码已绑定其他客户");
        }
    }

    private void clearPrimaryPhone(Long customerId) {
        List<CustomerPhone> phones = customerPhoneMapper.selectList(new LambdaQueryWrapper<CustomerPhone>()
            .eq(CustomerPhone::getCustomerId, customerId)
            .eq(CustomerPhone::getPrimaryFlag, true));
        phones.forEach(phone -> {
            phone.setPrimaryFlag(false);
            customerPhoneMapper.updateById(phone);
        });
    }

    private void syncPrimaryPhone(Customer customer, String normalizedPhone) {
        customer.setPrimaryPhone(normalizedPhone);
        customerMapper.updateById(customer);
    }

    private void applyPhoneRequest(CustomerPhone phone, CustomerPhoneRequest request, String normalizedPhone) {
        phone.setPhoneNumber(request.getPhoneNumber().trim());
        phone.setNormalizedPhone(normalizedPhone);
        phone.setPhoneType(request.getPhoneType() == null ? inferPhoneType(normalizedPhone) : request.getPhoneType());
        phone.setPhoneLabel(request.getPhoneLabel());
        phone.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        phone.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
    }

    private String inferPhoneType(String phoneNumber) {
        return phoneNumber != null && phoneNumber.matches("^1[3-9]\\d{9}$") ? "MOBILE" : "OTHER";
    }
}
