package org.dromara.customer.customer.service.impl;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.SkillGroupMember;
import org.dromara.agent.domain.SkillGroup;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.mapper.SkillGroupMemberMapper;
import org.dromara.agent.mapper.SkillGroupMapper;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.customer.domain.Customer;
import org.dromara.customer.customer.domain.CustomerAssignment;
import org.dromara.customer.customer.domain.CustomerFollowUp;
import org.dromara.customer.customer.domain.CustomerPhone;
import org.dromara.customer.customer.domain.CustomerImportRow;
import org.dromara.customer.customer.domain.request.CustomerAssignmentRequest;
import org.dromara.customer.customer.domain.request.CreateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerImportData;
import org.dromara.customer.customer.domain.request.CustomerPhoneRequest;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;
import org.dromara.customer.customer.domain.request.UpdateCustomerRequest;
import org.dromara.customer.customer.domain.response.CustomerFollowUpResponse;
import org.dromara.customer.customer.domain.response.CustomerPhoneResponse;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.mapper.CustomerFollowUpMapper;
import org.dromara.customer.customer.mapper.CustomerAssignmentMapper;
import org.dromara.customer.customer.mapper.CustomerMapper;
import org.dromara.customer.customer.mapper.CustomerPhoneMapper;
import org.dromara.customer.customer.mapper.CustomerImportRowMapper;
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
import java.util.HashSet;
import java.util.HashMap;
import java.util.Objects;
import java.util.Collections;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerApplicationServiceImpl implements CustomerApplicationService {
    private final CustomerMapper customerMapper;
    private final CustomerPhoneMapper customerPhoneMapper;
    private final CustomerImportRowMapper customerImportRowMapper;
    private final CustomerAssignmentMapper customerAssignmentMapper;
    private final CustomerFollowUpMapper followUpMapper;
    private final AgentMapper agentMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final SkillGroupMemberMapper skillGroupMemberMapper;
    private final CustomerPhoneNormalizer phoneNormalizer;
    private final DynamicFormSubmissionService formSubmissionService;
    private final CallBusinessAssociationService callBusinessAssociationService;

    @Override
    public TableDataInfo<CustomerResponse> page(CustomerPageQuery query, PageQuery pageQuery) {
        CustomerPageQuery safeQuery = query == null ? new CustomerPageQuery() : query;
        boolean unassignedOnly = isAssignmentState(safeQuery, "UNASSIGNED");
        if (unassignedOnly && (!isAssignmentAdmin() || hasOwnerFilter(safeQuery))) {
            return new TableDataInfo<>(List.of(), 0L);
        }
        Set<Long> importCustomerIds = resolveImportCustomerIds(safeQuery);
        if (importCustomerIds != null && importCustomerIds.isEmpty()) {
            return new TableDataInfo<>(List.of(), 0L);
        }
        Set<Long> assignmentCustomerIds = unassignedOnly ? loadAllActiveAssignedCustomerIds() : resolveAssignmentCustomerIds(safeQuery);
        if (!unassignedOnly && assignmentCustomerIds != null && assignmentCustomerIds.isEmpty()) {
            return new TableDataInfo<>(List.of(), 0L);
        }
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<Customer>()
            .like(safeQuery.getCustomerName() != null && !safeQuery.getCustomerName().isBlank(), Customer::getCustomerName, safeQuery.getCustomerName())
            .orderByDesc(Customer::getCreateTime);
        if (importCustomerIds != null) {
            wrapper.in(Customer::getId, importCustomerIds);
        }
        if (unassignedOnly) {
            if (!assignmentCustomerIds.isEmpty()) {
                wrapper.notIn(Customer::getId, assignmentCustomerIds);
            }
        } else if (assignmentCustomerIds != null) {
            wrapper.in(Customer::getId, assignmentCustomerIds);
        }
        if (safeQuery.getPrimaryPhone() != null && !safeQuery.getPrimaryPhone().isBlank()) {
            String keyword = phoneNormalizer.clean(safeQuery.getPrimaryPhone());
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
        List<Long> customerIds = page.getRecords().stream().map(Customer::getId).toList();
        Map<Long, List<CustomerPhoneResponse>> phonesByCustomer = loadPhones(customerIds);
        Map<Long, CustomerAssignment> assignmentsByCustomer = loadActiveAssignments(customerIds);
        return new TableDataInfo<>(page.getRecords().stream()
            .map(customer -> toResponse(customer, phonesByCustomer.get(customer.getId()), assignmentsByCustomer.get(customer.getId())))
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
    public void assign(CustomerAssignmentRequest request) {
        List<Long> customerIds = request.getCustomerIds() == null ? List.of() : request.getCustomerIds().stream()
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        if (customerIds.isEmpty()) {
            throw new ServiceException("请选择需要分配的客户");
        }
        List<Long> allocationAgentIds = validateAssignmentTarget(request);
        customerIds.forEach(this::requireCustomer);
        Map<Long, CustomerAssignment> previousAssignments = new HashMap<>();
        for (int start = 0; start < customerIds.size(); start += 500) {
            List<Long> part = customerIds.subList(start, Math.min(start + 500, customerIds.size()));
            customerAssignmentMapper.selectList(new LambdaQueryWrapper<CustomerAssignment>()
                    .in(CustomerAssignment::getCustomerId, part)
                    .eq(CustomerAssignment::getEnabled, true)
                    .orderByDesc(CustomerAssignment::getCreateTime))
                .forEach(item -> previousAssignments.putIfAbsent(item.getCustomerId(), item));
            customerAssignmentMapper.update(null, new LambdaUpdateWrapper<CustomerAssignment>()
                .set(CustomerAssignment::getEnabled, false)
                .in(CustomerAssignment::getCustomerId, part)
                .eq(CustomerAssignment::getEnabled, true));
        }
        for (int index = 0; index < customerIds.size(); index++) {
            Long customerId = customerIds.get(index);
            CustomerAssignment previous = previousAssignments.get(customerId);
            CustomerAssignment assignment = new CustomerAssignment();
            assignment.setCustomerId(customerId);
            assignment.setCustomerType(preferRequestValue(request.getCustomerType(), previous == null ? null : previous.getCustomerType()));
            assignment.setSourceChannel(preferRequestValue(request.getSourceChannel(), previous == null ? null : previous.getSourceChannel()));
            assignment.setTags(preferRequestValue(request.getTags(), previous == null ? null : previous.getTags()));
            assignment.setSkillGroupId(request.getSkillGroupId());
            assignment.setAgentId(allocationAgentIds.isEmpty()
                ? request.getAgentId()
                : allocationAgentIds.get(index % allocationAgentIds.size()));
            assignment.setAssignmentSource("MANUAL");
            assignment.setRemark(preferRequestValue(request.getRemark(), previous == null ? null : previous.getRemark()));
            assignment.setEnabled(true);
            customerAssignmentMapper.insert(assignment);
        }
    }

    private String preferRequestValue(String requested, String current) {
        String cleaned = cleanText(requested);
        return cleaned == null ? cleanText(current) : cleaned;
    }

    private List<Long> validateAssignmentTarget(CustomerAssignmentRequest request) {
        if (request.getSkillGroupId() == null) {
            throw new ServiceException("请选择归属技能组");
        }
        SkillGroup group = skillGroupMapper.selectById(request.getSkillGroupId());
        if (group == null || !Boolean.TRUE.equals(group.getEnabled())) {
            throw new ServiceException("归属技能组不存在或已停用");
        }
        if ("EVEN".equalsIgnoreCase(request.getAllocationMode())) {
            List<Long> memberAgentIds = skillGroupMemberMapper.selectList(new LambdaQueryWrapper<SkillGroupMember>()
                    .eq(SkillGroupMember::getSkillGroupId, request.getSkillGroupId())
                    .orderByAsc(SkillGroupMember::getPriority)
                    .orderByAsc(SkillGroupMember::getId))
                .stream()
                .map(SkillGroupMember::getAgentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            if (memberAgentIds.isEmpty()) {
                throw new ServiceException("所选技能组没有可分配的坐席");
            }
            Set<Long> enabledAgentIds = agentMapper.selectList(new LambdaQueryWrapper<Agent>()
                    .in(Agent::getId, memberAgentIds)
                    .eq(Agent::getEnabled, true))
                .stream()
                .map(Agent::getId)
                .collect(Collectors.toSet());
            List<Long> availableAgentIds = memberAgentIds.stream()
                .filter(enabledAgentIds::contains)
                .toList();
            if (availableAgentIds.isEmpty()) {
                throw new ServiceException("所选技能组没有启用的坐席");
            }
            if (request.getAgentIds() == null) {
                return availableAgentIds;
            }
            List<Long> requestedAgentIds = request.getAgentIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            if (requestedAgentIds.isEmpty()) {
                throw new ServiceException("请选择参与平均分配的坐席");
            }
            Set<Long> availableAgentIdSet = new HashSet<>(availableAgentIds);
            if (!availableAgentIdSet.containsAll(requestedAgentIds)) {
                throw new ServiceException("所选坐席包含不属于当前技能组或已停用的坐席");
            }
            Set<Long> requestedAgentIdSet = new HashSet<>(requestedAgentIds);
            return availableAgentIds.stream()
                .filter(requestedAgentIdSet::contains)
                .toList();
        }
        if (request.getAgentId() == null) {
            return List.of();
        }
        Agent agent = agentMapper.selectById(request.getAgentId());
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) {
            throw new ServiceException("归属坐席不存在或已停用");
        }
        Long memberCount = skillGroupMemberMapper.selectCount(new LambdaQueryWrapper<SkillGroupMember>()
            .eq(SkillGroupMember::getSkillGroupId, request.getSkillGroupId())
            .eq(SkillGroupMember::getAgentId, request.getAgentId()));
        if (memberCount == null || memberCount == 0) {
            throw new ServiceException("所选坐席不属于当前技能组");
        }
        return List.of(request.getAgentId());
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
    @Transactional(rollbackFor = Exception.class)
    public void claimCurrentAgent(Long id, String businessCallId) {
        requireCustomer(id);
        Long handlingAgentId = callBusinessAssociationService.findHandlingAgentId(businessCallId);
        if (handlingAgentId == null) {
            throw new ServiceException("当前通话尚未确认接听坐席，无法归属客户");
        }
        Long userId = LoginHelper.getUserId();
        Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
            .eq(Agent::getId, handlingAgentId)
            .eq(Agent::getUserId, userId)
            .eq(Agent::getEnabled, true)
            .last("limit 1"));
        if (agent == null) {
            throw new ServiceException("当前通话接听坐席与登录账号不一致，无法归属客户");
        }
        CustomerAssignment previous = loadActiveAssignment(id);
        customerAssignmentMapper.update(null, new LambdaUpdateWrapper<CustomerAssignment>()
            .set(CustomerAssignment::getEnabled, false)
            .eq(CustomerAssignment::getCustomerId, id)
            .eq(CustomerAssignment::getEnabled, true));

        List<Long> skillGroupIds = skillGroupMemberMapper.selectList(new LambdaQueryWrapper<SkillGroupMember>()
                .eq(SkillGroupMember::getAgentId, agent.getId())
                .orderByAsc(SkillGroupMember::getPriority)
                .orderByAsc(SkillGroupMember::getId))
            .stream()
            .map(SkillGroupMember::getSkillGroupId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Long skillGroupId = previous != null && previous.getSkillGroupId() != null
            ? previous.getSkillGroupId()
            : (skillGroupIds.size() == 1 ? skillGroupIds.get(0) : null);

        CustomerAssignment assignment = new CustomerAssignment();
        assignment.setCustomerId(id);
        assignment.setCustomerType(previous == null ? null : previous.getCustomerType());
        assignment.setSourceChannel(previous == null ? null : previous.getSourceChannel());
        assignment.setTags(previous == null ? null : previous.getTags());
        assignment.setSkillGroupId(skillGroupId);
        assignment.setAgentId(agent.getId());
        assignment.setAssignmentSource("CALL_WORKSPACE");
        assignment.setImportBatchId(previous == null ? null : previous.getImportBatchId());
        assignment.setRemark(previous == null ? null : previous.getRemark());
        assignment.setEnabled(true);
        customerAssignmentMapper.insert(assignment);
        callBusinessAssociationService.associateCustomer(businessCallId, id);
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
    public TableDataInfo<CustomerFollowUpResponse> pageFollowUps(Long customerId, PageQuery pageQuery) {
        requireCustomer(customerId);
        IPage<CustomerFollowUpResponse> page = followUpMapper.selectPage(pageQuery.build(), new LambdaQueryWrapper<CustomerFollowUp>()
                .eq(CustomerFollowUp::getCustomerId, customerId)
                .orderByDesc(CustomerFollowUp::getCreateTime))
            .convert(this::toFollowUpResponse);
        return TableDataInfo.build(page);
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordOutboundResult(Long customerId, Long attemptId, String content, String tag) {
        requireCustomer(customerId);
        if (attemptId == null) return;
        long existing = followUpMapper.selectCount(new LambdaQueryWrapper<CustomerFollowUp>()
            .eq(CustomerFollowUp::getSourceType, "AUTO_OUTBOUND")
            .eq(CustomerFollowUp::getSourceId, attemptId));
        if (existing == 0) {
            CustomerFollowUp followUp = new CustomerFollowUp();
            followUp.setCustomerId(customerId);
            followUp.setContent(content);
            followUp.setFollowUpByName("自动外呼");
            followUp.setSourceType("AUTO_OUTBOUND");
            followUp.setSourceId(attemptId);
            try {
                followUpMapper.insert(followUp);
            } catch (DuplicateKeyException ignored) {
                // 通话结束事件可能重复到达，来源唯一键保证只生成一条跟进记录。
            }
        }
        appendAssignmentTag(customerId, tag);
    }

    private void appendAssignmentTag(Long customerId, String tag) {
        String normalized = cleanText(tag);
        if (normalized == null) return;
        CustomerAssignment assignment = loadActiveAssignment(customerId);
        if (assignment == null) return;
        List<String> tags = new java.util.ArrayList<>();
        if (hasText(assignment.getTags())) {
            tags.addAll(java.util.Arrays.stream(assignment.getTags().split("[,，]"))
                .map(String::trim).filter(value -> !value.isEmpty()).toList());
        }
        if (tags.stream().noneMatch(value -> value.equalsIgnoreCase(normalized))) {
            tags.add(normalized);
            assignment.setTags(String.join(",", tags));
            customerAssignmentMapper.updateById(assignment);
        }
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
        Long templateId = customer.getTemplateId();
        if (templateId == null) {
            templateId = formSubmissionService.getLatestTemplateId(FormBusinessType.CUSTOMER, customer.getId());
            response.setTemplateId(templateId);
        }
        response.setFormData(templateId == null
            ? Map.of()
            : formSubmissionService.getFormData(FormBusinessType.CUSTOMER, customer.getId()));
        return response;
    }

    private CustomerResponse toResponse(Customer customer) {
        return toResponse(customer, listPhoneResponses(customer.getId()), loadActiveAssignment(customer.getId()));
    }

    private CustomerResponse toResponse(Customer customer, List<CustomerPhoneResponse> phones, CustomerAssignment assignment) {
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
        Long templateId = customer.getTemplateId();
        if (templateId != null) {
            response.setFormData(formSubmissionService.getFormData(FormBusinessType.CUSTOMER, customer.getId()));
        }
        applyAssignment(response, assignment);
        return response;
    }

    private void applyAssignment(CustomerResponse response, CustomerAssignment assignment) {
        if (assignment == null) {
            return;
        }
        response.setAssignmentId(assignment.getId());
        response.setCustomerType(assignment.getCustomerType());
        response.setSourceChannel(assignment.getSourceChannel());
        response.setTags(assignment.getTags());
        response.setSkillGroupId(assignment.getSkillGroupId());
        response.setAgentId(assignment.getAgentId());
        response.setAssignmentSource(assignment.getAssignmentSource());
        response.setImportBatchId(assignment.getImportBatchId());
        response.setAssignmentRemark(assignment.getRemark());
    }

    private CustomerAssignment loadActiveAssignment(Long customerId) {
        return customerAssignmentMapper.selectOne(new LambdaQueryWrapper<CustomerAssignment>()
            .eq(CustomerAssignment::getCustomerId, customerId)
            .eq(CustomerAssignment::getEnabled, true)
            .orderByDesc(CustomerAssignment::getCreateTime)
            .last("LIMIT 1"));
    }

    private Map<Long, CustomerAssignment> loadActiveAssignments(List<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        return customerAssignmentMapper.selectList(new LambdaQueryWrapper<CustomerAssignment>()
                .in(CustomerAssignment::getCustomerId, customerIds)
                .eq(CustomerAssignment::getEnabled, true)
                .orderByDesc(CustomerAssignment::getCreateTime))
            .stream()
            .collect(Collectors.toMap(CustomerAssignment::getCustomerId, Function.identity(), (first, ignored) -> first));
    }

    private Set<Long> resolveAssignmentCustomerIds(CustomerPageQuery query) {
        LambdaQueryWrapper<CustomerAssignment> wrapper = activeAssignmentQuery();
        boolean restricted = applyVisibleScope(wrapper);
        boolean filtered = applyAssignmentFilters(wrapper, query);
        if (isAssignmentState(query, "ASSIGNED")) {
            applyOwnerAssignedScope(wrapper);
            filtered = true;
        }
        if (!restricted && !filtered) {
            return null;
        }
        return customerAssignmentMapper.selectList(wrapper).stream()
            .map(CustomerAssignment::getCustomerId)
            .collect(Collectors.toSet());
    }

    private LambdaQueryWrapper<CustomerAssignment> activeAssignmentQuery() {
        return new LambdaQueryWrapper<CustomerAssignment>()
            .eq(CustomerAssignment::getEnabled, true);
    }

    private boolean applyVisibleScope(LambdaQueryWrapper<CustomerAssignment> wrapper) {
        if (isAssignmentAdmin()) {
            return false;
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            wrapper.eq(CustomerAssignment::getCustomerId, -1L);
            return true;
        }
        List<Agent> agents = agentMapper.selectList(new LambdaQueryWrapper<Agent>()
            .eq(Agent::getUserId, userId)
            .eq(Agent::getEnabled, true));
        Set<Long> agentIds = agents.stream().map(Agent::getId).collect(Collectors.toSet());
        if (agentIds.isEmpty()) {
            wrapper.eq(CustomerAssignment::getCustomerId, -1L);
            return true;
        }
        Set<Long> skillGroupIds = skillGroupMemberMapper.selectList(new LambdaQueryWrapper<SkillGroupMember>()
                .in(SkillGroupMember::getAgentId, agentIds))
            .stream()
            .map(SkillGroupMember::getSkillGroupId)
            .collect(Collectors.toSet());
        wrapper.and(scope -> {
            scope.in(CustomerAssignment::getAgentId, agentIds);
            if (!skillGroupIds.isEmpty()) {
                scope.or(groupOwned -> groupOwned
                    .in(CustomerAssignment::getSkillGroupId, skillGroupIds)
                    .isNull(CustomerAssignment::getAgentId));
            }
        });
        return true;
    }

    private boolean isAssignmentAdmin() {
        return LoginHelper.isSuperAdmin() || LoginHelper.isTenantAdmin();
    }

    private boolean isAssignmentState(CustomerPageQuery query, String state) {
        return query != null && hasText(query.getAssignmentState()) && state.equalsIgnoreCase(query.getAssignmentState().trim());
    }

    private boolean hasOwnerFilter(CustomerPageQuery query) {
        return query != null && (query.getSkillGroupId() != null || query.getAgentId() != null);
    }

    private Set<Long> resolveImportCustomerIds(CustomerPageQuery query) {
        if (query.getImportTaskId() == null && query.getImportBatchId() == null) {
            return null;
        }
        return customerImportRowMapper.selectList(new LambdaQueryWrapper<CustomerImportRow>()
                .eq(query.getImportTaskId() != null, CustomerImportRow::getTaskId, query.getImportTaskId())
                .eq(query.getImportBatchId() != null, CustomerImportRow::getBatchId, query.getImportBatchId())
                .eq(CustomerImportRow::getStatus, "IMPORTED")
                .isNotNull(CustomerImportRow::getCustomerId))
            .stream()
            .map(CustomerImportRow::getCustomerId)
            .collect(Collectors.toSet());
    }

    private Set<Long> loadAllActiveAssignedCustomerIds() {
        return customerAssignmentMapper.selectList(ownerAssignedQuery()).stream()
            .map(CustomerAssignment::getCustomerId)
            .collect(Collectors.toSet());
    }

    private LambdaQueryWrapper<CustomerAssignment> ownerAssignedQuery() {
        return applyOwnerAssignedScope(activeAssignmentQuery());
    }

    private LambdaQueryWrapper<CustomerAssignment> applyOwnerAssignedScope(LambdaQueryWrapper<CustomerAssignment> wrapper) {
        return wrapper.and(scope -> scope.isNotNull(CustomerAssignment::getSkillGroupId)
            .or()
            .isNotNull(CustomerAssignment::getAgentId));
    }

    private boolean applyAssignmentFilters(LambdaQueryWrapper<CustomerAssignment> wrapper, CustomerPageQuery query) {
        boolean filtered = false;
        if (query.getSkillGroupId() != null) {
            wrapper.eq(CustomerAssignment::getSkillGroupId, query.getSkillGroupId());
            filtered = true;
        }
        if (query.getAgentId() != null) {
            wrapper.eq(CustomerAssignment::getAgentId, query.getAgentId());
            filtered = true;
        }
        if (hasText(query.getCustomerType())) {
            wrapper.like(CustomerAssignment::getCustomerType, query.getCustomerType().trim());
            filtered = true;
        }
        if (hasText(query.getSourceChannel())) {
            wrapper.like(CustomerAssignment::getSourceChannel, query.getSourceChannel().trim());
            filtered = true;
        }
        if (hasText(query.getTags())) {
            wrapper.like(CustomerAssignment::getTags, query.getTags().trim());
            filtered = true;
        }
        return filtered;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
