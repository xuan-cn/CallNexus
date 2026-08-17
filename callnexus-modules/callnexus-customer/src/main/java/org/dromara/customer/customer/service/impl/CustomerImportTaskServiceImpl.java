package org.dromara.customer.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.customer.domain.CustomerAssignment;
import org.dromara.customer.customer.domain.CustomerImportBatch;
import org.dromara.customer.customer.domain.CustomerImportRow;
import org.dromara.customer.customer.domain.CustomerImportTask;
import org.dromara.customer.customer.domain.request.CustomerImportTaskQuery;
import org.dromara.customer.customer.domain.request.CustomerImportTaskRequest;
import org.dromara.customer.customer.domain.response.CustomerImportTaskResponse;
import org.dromara.customer.customer.mapper.CustomerAssignmentMapper;
import org.dromara.customer.customer.mapper.CustomerImportBatchMapper;
import org.dromara.customer.customer.mapper.CustomerImportRowMapper;
import org.dromara.customer.customer.mapper.CustomerImportTaskMapper;
import org.dromara.customer.customer.service.CustomerImportTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerImportTaskServiceImpl implements CustomerImportTaskService {
    private final CustomerImportTaskMapper taskMapper;
    private final CustomerImportBatchMapper batchMapper;
    private final CustomerImportRowMapper rowMapper;
    private final CustomerAssignmentMapper assignmentMapper;

    @Override
    public TableDataInfo<CustomerImportTaskResponse> page(CustomerImportTaskQuery query, PageQuery pageQuery) {
        CustomerImportTaskQuery safe = query == null ? new CustomerImportTaskQuery() : query;
        LambdaQueryWrapper<CustomerImportTask> wrapper = new LambdaQueryWrapper<CustomerImportTask>()
            .like(hasText(safe.getTaskName()), CustomerImportTask::getTaskName, trim(safe.getTaskName()))
            .eq(hasText(safe.getStatus()), CustomerImportTask::getStatus, trim(safe.getStatus()))
            .orderByDesc(CustomerImportTask::getCreateTime);
        Page<CustomerImportTask> page = taskMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public CustomerImportTaskResponse get(Long taskId) {
        return toResponse(require(taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CustomerImportTaskRequest request) {
        CustomerImportTask task = new CustomerImportTask();
        task.setTaskCode("CIT" + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        task.setStatus("ENABLED");
        apply(task, request);
        taskMapper.insert(task);
        return task.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long taskId, CustomerImportTaskRequest request) {
        CustomerImportTask task = require(taskId);
        apply(task, request);
        taskMapper.updateById(task);
    }

    @Override
    public void updateStatus(Long taskId, String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new ServiceException("任务状态不正确");
        }
        CustomerImportTask task = require(taskId);
        task.setStatus(status);
        taskMapper.updateById(task);
    }

    @Override
    public void delete(Long taskId) {
        require(taskId);
        if (batchMapper.selectCount(new LambdaQueryWrapper<CustomerImportBatch>().eq(CustomerImportBatch::getTaskId, taskId)) > 0) {
            throw new ServiceException("任务已有导入记录，不能删除，请停用任务");
        }
        taskMapper.deleteById(taskId);
    }

    @Override
    public void validateCustomers(Long taskId, List<Long> customerIds) {
        require(taskId);
        if (customerIds == null || customerIds.isEmpty()) {
            throw new ServiceException("请选择需要分配的客户");
        }
        List<Long> distinctIds = customerIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        Set<Long> imported = new HashSet<>();
        for (int start = 0; start < distinctIds.size(); start += 500) {
            List<Long> part = distinctIds.subList(start, Math.min(start + 500, distinctIds.size()));
            rowMapper.selectList(new LambdaQueryWrapper<CustomerImportRow>()
                    .eq(CustomerImportRow::getTaskId, taskId)
                    .eq(CustomerImportRow::getStatus, "IMPORTED")
                    .in(CustomerImportRow::getCustomerId, part)
                    .isNotNull(CustomerImportRow::getCustomerId))
                .stream().map(CustomerImportRow::getCustomerId).forEach(imported::add);
        }
        if (imported.size() != distinctIds.size()) {
            throw new ServiceException("分配数据中包含不属于当前任务的客户");
        }
    }

    private CustomerImportTask require(Long taskId) {
        CustomerImportTask task = taskMapper.selectById(taskId);
        if (task == null) throw new ServiceException("资料导入任务不存在");
        return task;
    }

    private void apply(CustomerImportTask task, CustomerImportTaskRequest request) {
        task.setTaskName(trim(request.getTaskName()));
        task.setDescription(trim(request.getDescription()));
        task.setDuplicateStrategy(hasText(request.getDuplicateStrategy()) ? trim(request.getDuplicateStrategy()) : "SKIP");
        task.setFormTemplateId(request.getFormTemplateId());
        task.setFieldMappingJson(trim(request.getFieldMappingJson()));
        task.setDefaultCustomerType(trim(request.getDefaultCustomerType()));
        task.setDefaultSourceChannel(trim(request.getDefaultSourceChannel()));
        task.setDefaultTags(trim(request.getDefaultTags()));
        task.setDefaultRemark(trim(request.getDefaultRemark()));
    }

    private CustomerImportTaskResponse toResponse(CustomerImportTask task) {
        List<CustomerImportBatch> batches = batchMapper.selectList(new LambdaQueryWrapper<CustomerImportBatch>()
            .eq(CustomerImportBatch::getTaskId, task.getId()).orderByDesc(CustomerImportBatch::getCreateTime));
        List<CustomerImportRow> rows = rowMapper.selectList(new LambdaQueryWrapper<CustomerImportRow>()
            .eq(CustomerImportRow::getTaskId, task.getId()).eq(CustomerImportRow::getStatus, "IMPORTED"));
        Set<Long> customerIds = new HashSet<>();
        rows.stream().map(CustomerImportRow::getCustomerId).filter(java.util.Objects::nonNull).forEach(customerIds::add);
        Set<Long> assignedIds = new HashSet<>();
        if (!customerIds.isEmpty()) {
            assignmentMapper.selectList(new LambdaQueryWrapper<CustomerAssignment>()
                    .in(CustomerAssignment::getCustomerId, customerIds)
                    .eq(CustomerAssignment::getEnabled, true)
                    .and(scope -> scope.isNotNull(CustomerAssignment::getSkillGroupId).or().isNotNull(CustomerAssignment::getAgentId)))
                .stream().map(CustomerAssignment::getCustomerId).forEach(assignedIds::add);
        }
        CustomerImportTaskResponse response = new CustomerImportTaskResponse();
        response.setId(task.getId());
        response.setTaskCode(task.getTaskCode());
        response.setTaskName(task.getTaskName());
        response.setDescription(task.getDescription());
        response.setStatus(task.getStatus());
        response.setDuplicateStrategy(task.getDuplicateStrategy());
        response.setFormTemplateId(task.getFormTemplateId());
        response.setFieldMappingJson(task.getFieldMappingJson());
        response.setDefaultCustomerType(task.getDefaultCustomerType());
        response.setDefaultSourceChannel(task.getDefaultSourceChannel());
        response.setDefaultTags(task.getDefaultTags());
        response.setDefaultRemark(task.getDefaultRemark());
        response.setBatchCount(batches.size());
        response.setImportedCount(customerIds.size());
        response.setFailedCount(batches.stream().mapToLong(item -> item.getFailedCount() == null ? 0 : item.getFailedCount()).sum());
        response.setAssignedCount(assignedIds.size());
        response.setUnassignedCount(Math.max(0, customerIds.size() - assignedIds.size()));
        response.setLastImportTime(batches.isEmpty() ? null : batches.get(0).getCreateTime());
        response.setCreateTime(task.getCreateTime());
        return response;
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String trim(String value) { return hasText(value) ? value.trim() : null; }
}
