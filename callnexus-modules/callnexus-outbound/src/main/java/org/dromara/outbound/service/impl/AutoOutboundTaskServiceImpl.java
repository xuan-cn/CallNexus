package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;
import org.dromara.customer.customer.domain.response.CustomerImportTaskResponse;
import org.dromara.customer.customer.domain.response.CustomerPhoneResponse;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.customer.service.CustomerImportTaskService;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.OutboundTaskCallWindow;
import org.dromara.outbound.domain.OutboundTaskRetryRule;
import org.dromara.outbound.domain.OutboundTaskSource;
import org.dromara.outbound.domain.request.AutoOutboundSourceRequest;
import org.dromara.outbound.domain.request.AutoOutboundTaskRequest;
import org.dromara.outbound.domain.response.AutoOutboundMaterializeResponse;
import org.dromara.outbound.domain.response.AutoOutboundMonitorResponse;
import org.dromara.outbound.domain.response.AutoOutboundMemberResponse;
import org.dromara.outbound.domain.response.AutoOutboundSourceResponse;
import org.dromara.outbound.domain.response.AutoOutboundTaskResponse;
import org.dromara.outbound.domain.response.OutboundBlacklistMatch;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundAttemptMapper;
import org.dromara.outbound.mapper.AutoOutboundDispatchMapper;
import org.dromara.outbound.domain.AutoOutboundDispatch;
import org.dromara.outbound.domain.OutboundAttempt;
import org.dromara.outbound.mapper.OutboundTaskCallWindowMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.mapper.OutboundTaskRetryRuleMapper;
import org.dromara.outbound.mapper.OutboundTaskSourceMapper;
import org.dromara.outbound.service.AutoOutboundTaskService;
import org.dromara.outbound.service.OutboundBlacklistChecker;
import org.dromara.outbound.service.PhoneNumberNormalizer;
import org.dromara.outbound.service.OutboundResultSuggestionService;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.outboundline.domain.response.OutboundLinePolicyResponse;
import org.dromara.resource.outboundline.service.OutboundLinePolicyService;
import org.dromara.resource.phone.domain.response.PhoneNumberResponse;
import org.dromara.resource.phone.service.PhoneNumberApplicationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AutoOutboundTaskServiceImpl implements AutoOutboundTaskService {
    private static final String TASK_TYPE = "AUTO";
    private static final String SOURCE_TYPE_IMPORT_TASK = "IMPORT_TASK";
    private static final Set<String> EDITABLE_STATUSES = Set.of("DRAFT", "PAUSED", "STOPPED");

    private final OutboundTaskMapper taskMapper;
    private final OutboundMemberMapper memberMapper;
    private final OutboundTaskCallWindowMapper callWindowMapper;
    private final OutboundTaskRetryRuleMapper retryRuleMapper;
    private final OutboundTaskSourceMapper sourceMapper;
    private final CustomerApplicationService customerService;
    private final CustomerImportTaskService customerImportTaskService;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final OutboundBlacklistChecker blacklistChecker;
    private final AutoOutboundDispatchMapper dispatchMapper;
    private final OutboundAttemptMapper attemptMapper;
    private final OutboundResultSuggestionService resultSuggestionService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final PhoneNumberApplicationService phoneNumberService;
    private final OutboundLinePolicyService outboundLinePolicyService;

    @Override
    public List<AutoOutboundTaskResponse> list() {
        return taskMapper.selectList(new LambdaQueryWrapper<OutboundTask>()
                .eq(OutboundTask::getTaskType, TASK_TYPE)
                .orderByDesc(OutboundTask::getCreateTime))
            .stream().map(this::toResponse).toList();
    }

    @Override
    public AutoOutboundTaskResponse get(Long id) {
        return toResponse(requireTask(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AutoOutboundTaskRequest request) {
        validate(request);
        OutboundTask task = new OutboundTask();
        apply(task, request);
        task.setTaskType(TASK_TYPE);
        task.setStatus("DRAFT");
        task.setExecutionRound(1);
        task.setAutoRetryEnabled(false);
        task.setAutoAssignDueRetry(false);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("外呼任务编码已存在");
        }
        replacePolicies(task.getId(), request);
        return task.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AutoOutboundTaskRequest request) {
        validate(request);
        OutboundTask task = requireTask(id);
        if (!EDITABLE_STATUSES.contains(task.getStatus())) {
            throw new ServiceException("运行中的自动外呼任务不能修改，请先暂停或停止任务");
        }
        apply(task, request);
        task.setVersion(request.getVersion());
        try {
            if (taskMapper.updateById(task) == 0) {
                throw new ServiceException("自动外呼任务已被其他用户修改，请刷新后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("外呼任务编码已存在");
        }
        replacePolicies(id, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        OutboundTask task = requireTask(id);
        if ("RUNNING".equals(task.getStatus())) {
            throw new ServiceException("执行中的自动外呼任务不能删除");
        }
        cancelReadyDispatches(id, "任务删除，取消尚未拨出的调度单");
        if (hasActiveCalls(id)) {
            throw new ServiceException("任务仍有活动拨号，暂时不能删除");
        }
        dispatchMapper.delete(new LambdaQueryWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getTaskId, id));
        attemptMapper.delete(new LambdaQueryWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getTaskId, id));
        memberMapper.delete(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, id));
        callWindowMapper.delete(new LambdaQueryWrapper<OutboundTaskCallWindow>()
            .eq(OutboundTaskCallWindow::getTaskId, id));
        retryRuleMapper.delete(new LambdaQueryWrapper<OutboundTaskRetryRule>()
            .eq(OutboundTaskRetryRule::getTaskId, id));
        sourceMapper.delete(new LambdaQueryWrapper<OutboundTaskSource>()
            .eq(OutboundTaskSource::getTaskId, id));
        taskMapper.deleteById(task.getId());
    }

    @Override
    public void start(Long id) {
        OutboundTask task = requireTask(id);
        if (!Set.of("DRAFT", "PAUSED", "STOPPED").contains(task.getStatus())) {
            throw new ServiceException("当前状态不能启动自动外呼任务");
        }
        if (countMembers(id, List.of("PENDING", "RETRY", "SCHEDULED")) == 0) {
            throw new ServiceException("自动外呼任务没有待处理名单，请先配置资料来源并生成名单");
        }
        taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
            .eq(OutboundTask::getId, id)
            .eq(OutboundTask::getTaskType, TASK_TYPE)
            .set(OutboundTask::getStatus, "RUNNING")
            .set(task.getExecutionStartedAt() == null, OutboundTask::getExecutionStartedAt, LocalDateTime.now()));
    }

    @Override
    public void pause(Long id) {
        OutboundTask task = requireTask(id);
        if (!"RUNNING".equals(task.getStatus())) {
            throw new ServiceException("只有执行中的任务可以暂停");
        }
        updateStatus(id, "PAUSED");
    }

    @Override
    public void resume(Long id) {
        OutboundTask task = requireTask(id);
        if (!"PAUSED".equals(task.getStatus())) {
            throw new ServiceException("只有已暂停的任务可以继续");
        }
        if (countMembers(id, List.of("PENDING", "RETRY", "SCHEDULED")) == 0) {
            throw new ServiceException("自动外呼任务没有待处理名单");
        }
        updateStatus(id, "RUNNING");
    }

    @Override
    public void stop(Long id) {
        OutboundTask task = requireTask(id);
        if (Set.of("COMPLETED", "STOPPED").contains(task.getStatus())) {
            throw new ServiceException("任务已经结束");
        }
        updateStatus(id, "STOPPED");
        cancelReadyDispatches(id, "任务已停止");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rerun(Long id) {
        OutboundTask task = requireTask(id);
        if (!Set.of("COMPLETED", "STOPPED").contains(task.getStatus())) {
            throw new ServiceException("只有已完成或已停止的任务可以重新执行");
        }
        cancelReadyDispatches(id, "任务重新执行，取消上一轮待拨调度单");
        if (hasActiveCalls(id)) {
            throw new ServiceException("任务仍有活动拨号，请等待通话结束后重新执行");
        }
        long executable = memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, id)
            .ne(OutboundMember::getStatus, "BLOCKED"));
        if (executable == 0) {
            throw new ServiceException("当前任务没有可重新执行的客户名单");
        }
        memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, id)
            .ne(OutboundMember::getStatus, "BLOCKED")
            .set(OutboundMember::getStatus, "PENDING")
            .set(OutboundMember::getClaimedAgentId, null)
            .set(OutboundMember::getClaimedUserId, null)
            .set(OutboundMember::getClaimedAt, null)
            .set(OutboundMember::getLeaseExpiresAt, null)
            .set(OutboundMember::getScheduleKey, null)
            .set(OutboundMember::getScheduledAt, null)
            .set(OutboundMember::getBusinessCallId, null)
            .set(OutboundMember::getAttemptCount, 0)
            .set(OutboundMember::getResultCode, null)
            .set(OutboundMember::getResultRemark, null)
            .set(OutboundMember::getNextFollowUpAt, null)
            .set(OutboundMember::getCompletedAt, null)
            .set(OutboundMember::getCompletionReason, null));
        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
            .eq(OutboundTask::getId, id)
            .eq(OutboundTask::getTaskType, TASK_TYPE)
            .in(OutboundTask::getStatus, "COMPLETED", "STOPPED")
            .set(OutboundTask::getStatus, "RUNNING")
            .set(OutboundTask::getExecutionRound, (task.getExecutionRound() == null ? 1 : task.getExecutionRound()) + 1)
            .set(OutboundTask::getExecutionStartedAt, now)
            .set(OutboundTask::getLastScheduledAt, null)
            .set(OutboundTask::getLastScheduleSummary, "已重新执行，等待调度")
            .set(OutboundTask::getSchedulerOwner, null)
            .set(OutboundTask::getSchedulerLeaseUntil, null)
            .set(OutboundTask::getSchedulerHeartbeatAt, null));
    }

    @Override
    public List<AutoOutboundSourceResponse> listSources(Long taskId) {
        requireTask(taskId);
        Map<Long, String> names = new HashMap<>();
        return sourceMapper.selectList(new LambdaQueryWrapper<OutboundTaskSource>()
                .eq(OutboundTaskSource::getTaskId, taskId)
                .orderByAsc(OutboundTaskSource::getCreateTime))
            .stream().map(source -> toSourceResponse(source, names)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addSource(Long taskId, AutoOutboundSourceRequest request) {
        requireEditableTask(taskId);
        CustomerImportTaskResponse importTask = customerImportTaskService.get(request.getImportTaskId());
        if (!"ENABLED".equals(importTask.getStatus())) {
            throw new ServiceException("客户资料导入任务已停用，不能作为新的名单来源");
        }
        OutboundTaskSource source = new OutboundTaskSource();
        source.setTaskId(taskId);
        source.setImportTaskId(request.getImportTaskId());
        source.setImportBatchId(request.getImportBatchId());
        source.setCustomerType(trim(request.getCustomerType()));
        source.setTags(trim(request.getTags()));
        source.setSkillGroupId(request.getSkillGroupId());
        source.setAgentId(request.getAgentId());
        source.setAssignmentState(request.getAssignmentState());
        source.setPhoneStrategy(request.getPhoneStrategy());
        source.setPhoneLabel(trim(request.getPhoneLabel()));
        source.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        source.setFilterSummary(buildFilterSummary(importTask.getTaskName(), request));
        sourceMapper.insert(source);
        return source.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSource(Long taskId, Long sourceId) {
        requireEditableTask(taskId);
        if (memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId).eq(OutboundMember::getSourceId, sourceId)) > 0) {
            throw new ServiceException("该来源已经生成外呼名单，不能删除；可以停用任务后保留审计记录");
        }
        int deleted = sourceMapper.delete(new LambdaQueryWrapper<OutboundTaskSource>()
            .eq(OutboundTaskSource::getId, sourceId).eq(OutboundTaskSource::getTaskId, taskId));
        if (deleted == 0) {
            throw new ServiceException("自动外呼名单来源不存在");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AutoOutboundMaterializeResponse materialize(Long taskId) {
        requireEditableTask(taskId);
        List<OutboundTaskSource> sources = sourceMapper.selectList(new LambdaQueryWrapper<OutboundTaskSource>()
            .eq(OutboundTaskSource::getTaskId, taskId).eq(OutboundTaskSource::getEnabled, true)
            .orderByAsc(OutboundTaskSource::getCreateTime));
        if (sources.isEmpty()) {
            throw new ServiceException("请先配置至少一个启用的客户资料来源");
        }
        AutoOutboundMaterializeResponse result = new AutoOutboundMaterializeResponse();
        result.setSourceCount(sources.size());
        Set<Long> existingCustomers = memberMapper.selectList(new LambdaQueryWrapper<OutboundMember>()
                .eq(OutboundMember::getTaskId, taskId))
            .stream().map(OutboundMember::getCustomerId).collect(java.util.stream.Collectors.toSet());
        for (OutboundTaskSource source : sources) {
            materializeSource(taskId, source, existingCustomers, result);
        }
        if (result.getAddedCount() == 0 && result.getBlacklistedCount() == 0) {
            throw new ServiceException("当前来源没有可新增的客户名单，请检查筛选条件或现有名单");
        }
        return result;
    }

    @Override
    public TableDataInfo<AutoOutboundMemberResponse> pageMembers(
        Long taskId, String status, String phoneNumber, PageQuery pageQuery
    ) {
        requireTask(taskId);
        Page<OutboundMember> page = memberMapper.selectPage(pageQuery.build(), new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .eq(StringUtils.isNotBlank(status), OutboundMember::getStatus, status)
            .like(StringUtils.isNotBlank(phoneNumber), OutboundMember::getPhoneNumber, phoneNumber)
            .orderByDesc(OutboundMember::getCreateTime));
        List<AutoOutboundMemberResponse> responses = page.getRecords().stream().map(this::toMemberResponse).toList();
        if (!responses.isEmpty()) {
            Map<Long, OutboundAttempt> latestAttempts = new java.util.LinkedHashMap<>();
            attemptMapper.selectList(new LambdaQueryWrapper<OutboundAttempt>()
                    .in(OutboundAttempt::getMemberId, responses.stream().map(AutoOutboundMemberResponse::getId).toList())
                    .orderByDesc(OutboundAttempt::getStartedAt, OutboundAttempt::getId))
                .forEach(attempt -> latestAttempts.putIfAbsent(attempt.getMemberId(), attempt));
            responses.forEach(response -> applyLatestAttempt(response, latestAttempts.get(response.getId())));
        }
        return new TableDataInfo<>(responses, page.getTotal());
    }

    @Override
    public AutoOutboundMonitorResponse monitor(Long taskId) {
        OutboundTask task = requireTask(taskId);
        AutoOutboundMonitorResponse response = new AutoOutboundMonitorResponse();
        response.setTaskId(taskId);
        response.setTaskStatus(task.getStatus());
        response.setPendingCount(countMembers(taskId, List.of("PENDING", "RETRY")));
        response.setScheduledCount(dispatchMapper.selectCount(new LambdaQueryWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getTaskId, taskId).eq(AutoOutboundDispatch::getStatus, "READY")));
        response.setProcessingCount(dispatchMapper.selectCount(new LambdaQueryWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getTaskId, taskId).eq(AutoOutboundDispatch::getStatus, "PROCESSING")));
        response.setDialingCount(countMembers(taskId, List.of("DIALING")));
        response.setCompletedCount(countMembers(taskId, List.of("COMPLETED", "SKIPPED", "BLOCKED")));
        response.setActiveConcurrency(response.getProcessingCount() + response.getDialingCount());
        response.setQueuedCount(response.getPendingCount() + response.getScheduledCount());
        ZoneId zoneId = ZoneId.of(task.getScheduleTimezone());
        LocalDateTime todayStart = LocalDate.now(zoneId).atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        List<OutboundAttempt> todayAttempts = attemptMapper.selectList(new LambdaQueryWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getTaskId, taskId)
            .ge(task.getExecutionStartedAt() != null, OutboundAttempt::getStartedAt, task.getExecutionStartedAt())
            .ge(OutboundAttempt::getStartedAt, todayStart)
            .lt(OutboundAttempt::getStartedAt, tomorrowStart));
        long answered = todayAttempts.stream().filter(item -> item.getAnsweredAt() != null).count();
        response.setTodayCallCount(todayAttempts.size());
        response.setTodayAnsweredCount(answered);
        response.setTodayAnswerRate(todayAttempts.isEmpty() ? 0D
            : Math.round(answered * 10000D / todayAttempts.size()) / 100D);
        Map<String, List<OutboundAttempt>> failures = todayAttempts.stream()
            .filter(item -> item.getFailureCategory() != null)
            .collect(java.util.stream.Collectors.groupingBy(OutboundAttempt::getFailureCategory));
        response.setFailureMetrics(failures.entrySet().stream().map(entry -> {
            AutoOutboundMonitorResponse.FailureMetric metric = new AutoOutboundMonitorResponse.FailureMetric();
            metric.setCategory(entry.getKey());
            metric.setCategoryLabel(resultSuggestionService.failureCategoryLabel(entry.getKey()));
            metric.setCount(entry.getValue().size());
            metric.setRetryable(entry.getValue().stream().anyMatch(item -> Boolean.TRUE.equals(item.getRetryable())));
            return metric;
        }).sorted(Comparator.comparingLong(AutoOutboundMonitorResponse.FailureMetric::getCount).reversed()).toList());
        response.setSchedulerOwner(task.getSchedulerOwner());
        response.setSchedulerLeaseUntil(task.getSchedulerLeaseUntil());
        response.setSchedulerHeartbeatAt(task.getSchedulerHeartbeatAt());
        response.setLastScheduledAt(task.getLastScheduledAt());
        response.setLastScheduleSummary(task.getLastScheduleSummary());
        return response;
    }

    private void materializeSource(
        Long taskId,
        OutboundTaskSource source,
        Set<Long> existingCustomers,
        AutoOutboundMaterializeResponse result
    ) {
        CustomerPageQuery query = new CustomerPageQuery();
        query.setImportTaskId(source.getImportTaskId());
        query.setImportBatchId(source.getImportBatchId());
        query.setCustomerType(source.getCustomerType());
        query.setTags(source.getTags());
        query.setSkillGroupId(source.getSkillGroupId());
        query.setAgentId(source.getAgentId());
        if (!"ALL".equals(source.getAssignmentState())) {
            query.setAssignmentState(source.getAssignmentState());
        }
        int pageNum = 1;
        long loaded = 0;
        TableDataInfo<CustomerResponse> page;
        do {
            page = customerService.page(query, new PageQuery(500, pageNum++));
            for (CustomerResponse customer : page.getRows()) {
                result.setCandidateCount(result.getCandidateCount() + 1);
                loaded++;
                if (!existingCustomers.add(customer.getId())) {
                    result.setDuplicateCount(result.getDuplicateCount() + 1);
                    continue;
                }
                CustomerPhoneResponse phone = selectPhone(customer, source.getPhoneStrategy(), source.getPhoneLabel());
                String rawPhone = phone == null ? null : StringUtils.defaultIfBlank(
                    phone.getNormalizedPhone(), phone.getPhoneNumber());
                String normalized = phoneNumberNormalizer.normalize(rawPhone);
                if (!phoneNumberNormalizer.isValid(normalized)) {
                    existingCustomers.remove(customer.getId());
                    result.setInvalidPhoneCount(result.getInvalidPhoneCount() + 1);
                    continue;
                }
                OutboundMember member = new OutboundMember();
                member.setTaskId(taskId);
                member.setCustomerId(customer.getId());
                member.setCustomerName(customer.getCustomerName());
                member.setPhoneNumber(normalized);
                member.setSourceType(SOURCE_TYPE_IMPORT_TASK);
                member.setImportBatchId(source.getImportBatchId());
                member.setSourceId(source.getId());
                member.setSourceImportTaskId(source.getImportTaskId());
                member.setSourceImportBatchId(customer.getImportBatchId() == null
                    ? source.getImportBatchId() : customer.getImportBatchId());
                member.setCustomerPhoneId(phone.getId());
                member.setPhoneLabel(phone.getPhoneLabel());
                member.setPhonePriority(phone.getSortOrder());
                member.setAttemptCount(0);
                OutboundBlacklistMatch blacklist = blacklistChecker.check(taskId, normalized);
                if (blacklist == null) {
                    member.setStatus("PENDING");
                    result.setAddedCount(result.getAddedCount() + 1);
                } else {
                    member.setStatus("BLOCKED");
                    member.setBlockedReason(blacklist.getReason());
                    member.setBlockedBlacklistId(blacklist.getBlacklistId());
                    member.setBlockedAt(java.time.LocalDateTime.now());
                    result.setBlacklistedCount(result.getBlacklistedCount() + 1);
                }
                try {
                    memberMapper.insert(member);
                } catch (DuplicateKeyException exception) {
                    existingCustomers.remove(customer.getId());
                    result.setDuplicateCount(result.getDuplicateCount() + 1);
                    if (blacklist == null) {
                        result.setAddedCount(result.getAddedCount() - 1);
                    } else {
                        result.setBlacklistedCount(result.getBlacklistedCount() - 1);
                    }
                }
            }
        } while (loaded < page.getTotal());
    }

    private CustomerPhoneResponse selectPhone(CustomerResponse customer, String strategy, String phoneLabel) {
        List<CustomerPhoneResponse> enabled = customer.getPhones() == null ? List.of() : customer.getPhones().stream()
            .filter(phone -> !Boolean.FALSE.equals(phone.getEnabled()))
            .sorted(Comparator.comparing(CustomerPhoneResponse::getSortOrder,
                Comparator.nullsLast(Integer::compareTo)))
            .toList();
        CustomerPhoneResponse primary = enabled.stream()
            .filter(phone -> Boolean.TRUE.equals(phone.getPrimaryFlag())).findFirst().orElse(null);
        if ("LABEL_OR_PRIMARY".equals(strategy)) {
            CustomerPhoneResponse labeled = enabled.stream()
                .filter(phone -> StringUtils.equalsIgnoreCase(phoneLabel, phone.getPhoneLabel()))
                .findFirst().orElse(null);
            if (labeled != null) {
                return labeled;
            }
        }
        if (primary != null || "PRIMARY_ONLY".equals(strategy)) {
            return primary;
        }
        return enabled.isEmpty() ? null : enabled.get(0);
    }

    private String buildFilterSummary(String importTaskName, AutoOutboundSourceRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add("导入任务：" + importTaskName);
        if (request.getImportBatchId() != null) parts.add("批次：" + request.getImportBatchId());
        if (StringUtils.isNotBlank(request.getCustomerType())) parts.add("客户类型：" + request.getCustomerType().trim());
        if (StringUtils.isNotBlank(request.getTags())) parts.add("标签：" + request.getTags().trim());
        if (request.getSkillGroupId() != null) parts.add("技能组：" + request.getSkillGroupId());
        if (request.getAgentId() != null) parts.add("坐席：" + request.getAgentId());
        if (!"ALL".equals(request.getAssignmentState())) parts.add("归属：" + request.getAssignmentState());
        parts.add("号码：" + request.getPhoneStrategy()
            + (StringUtils.isBlank(request.getPhoneLabel()) ? "" : "（" + request.getPhoneLabel().trim() + "）"));
        return String.join("；", parts);
    }

    private AutoOutboundSourceResponse toSourceResponse(OutboundTaskSource source, Map<Long, String> names) {
        AutoOutboundSourceResponse response = new AutoOutboundSourceResponse();
        response.setId(source.getId());
        response.setTaskId(source.getTaskId());
        response.setImportTaskId(source.getImportTaskId());
        response.setImportTaskName(names.computeIfAbsent(source.getImportTaskId(), id -> customerImportTaskService.get(id).getTaskName()));
        response.setImportBatchId(source.getImportBatchId());
        response.setCustomerType(source.getCustomerType());
        response.setTags(source.getTags());
        response.setSkillGroupId(source.getSkillGroupId());
        response.setAgentId(source.getAgentId());
        response.setAssignmentState(source.getAssignmentState());
        response.setPhoneStrategy(source.getPhoneStrategy());
        response.setPhoneLabel(source.getPhoneLabel());
        response.setEnabled(source.getEnabled());
        response.setFilterSummary(source.getFilterSummary());
        response.setCreateTime(source.getCreateTime());
        return response;
    }

    private AutoOutboundMemberResponse toMemberResponse(OutboundMember member) {
        AutoOutboundMemberResponse response = new AutoOutboundMemberResponse();
        response.setId(member.getId());
        response.setCustomerId(member.getCustomerId());
        response.setCustomerName(member.getCustomerName());
        response.setPhoneNumber(member.getPhoneNumber());
        response.setCustomerPhoneId(member.getCustomerPhoneId());
        response.setPhoneLabel(member.getPhoneLabel());
        response.setSourceId(member.getSourceId());
        response.setSourceImportTaskId(member.getSourceImportTaskId());
        response.setSourceImportBatchId(member.getSourceImportBatchId());
        response.setStatus(member.getStatus());
        response.setAttemptCount(member.getAttemptCount());
        response.setBlockedReason(member.getBlockedReason());
        response.setCreateTime(member.getCreateTime());
        return response;
    }

    private void applyLatestAttempt(AutoOutboundMemberResponse response, OutboundAttempt attempt) {
        if (attempt == null) {
            return;
        }
        response.setLastResultCode(attempt.getResultCode());
        response.setLastResultLabel(resultSuggestionService.resultLabel(attempt.getResultCode()));
        response.setLastResultRemark(StringUtils.isNotBlank(attempt.getResultRemark())
            ? attempt.getResultRemark() : resultSuggestionService.hangupCauseLabel(attempt.getHangupCause()));
        response.setFailureCategory(attempt.getFailureCategory());
        response.setFailureCategoryLabel(resultSuggestionService.failureCategoryLabel(attempt.getFailureCategory()));
        response.setRetryable(attempt.getRetryable());
        response.setLastAttemptAt(attempt.getStartedAt());
    }

    private void requireEditableTask(Long taskId) {
        OutboundTask task = requireTask(taskId);
        if (!EDITABLE_STATUSES.contains(task.getStatus())) {
            throw new ServiceException("运行中的自动外呼任务不能调整名单来源，请先暂停或停止任务");
        }
    }

    private void validate(AutoOutboundTaskRequest request) {
        nodeQueryService.getEnabledConnection(request.getNodeId());
        if (request.getCallerNumberId() != null && request.getOutboundLinePolicyId() != null) {
            throw new ServiceException("指定外显号码与指定外呼策略不能同时配置");
        }
        if (request.getCallerNumberId() != null) {
            PhoneNumberResponse callerNumber = phoneNumberService.get(request.getCallerNumberId());
            if (callerNumber == null || !Boolean.TRUE.equals(callerNumber.getEnabled())
                || !request.getNodeId().equals(callerNumber.getNodeId())) {
                throw new ServiceException("指定外显号码不可用或不属于执行节点");
            }
        }
        if (request.getOutboundLinePolicyId() != null) {
            OutboundLinePolicyResponse policy = outboundLinePolicyService.get(request.getOutboundLinePolicyId());
            if (policy == null || !Boolean.TRUE.equals(policy.getEnabled())
                || !request.getNodeId().equals(policy.getNodeId())) {
                throw new ServiceException("指定外呼策略不可用或不属于执行节点");
            }
        }
        try {
            ZoneId.of(request.getScheduleTimezone());
        } catch (DateTimeException exception) {
            throw new ServiceException("任务时区不正确");
        }
        String expectedTarget = switch (request.getDialMode()) {
            case "AGENTLESS_AI" -> "AI_AGENT";
            case "AGENTLESS_IVR" -> "IVR_FLOW";
            case "PROGRESSIVE" -> "SKILL_GROUP";
            default -> null;
        };
        if (!request.getTargetType().equals(expectedTarget)) {
            throw new ServiceException("拨打模式与接听目标类型不匹配");
        }
        if (request.getMaxCallsPerDay() > request.getMaxCallsTotal()) {
            throw new ServiceException("每日最大呼叫次数不能大于任务总呼叫次数");
        }
        validateCallWindows(request.getCallWindows());
        Set<String> resultCodes = new HashSet<>();
        for (AutoOutboundTaskRequest.RetryRule rule : request.getRetryRules()) {
            if (!resultCodes.add(rule.getResultCode())) {
                throw new ServiceException("同一呼叫结果只能配置一条重试规则");
            }
            if (Boolean.TRUE.equals(rule.getRetryEnabled()) && rule.getMaxRetryCount() < 1) {
                throw new ServiceException("启用重试时最大重试次数不能小于1");
            }
        }
    }

    private void validateCallWindows(List<AutoOutboundTaskRequest.CallWindow> windows) {
        for (AutoOutboundTaskRequest.CallWindow window : windows) {
            if (!window.getStartTime().isBefore(window.getEndTime())) {
                throw new ServiceException("呼叫时段开始时间必须早于结束时间，第一版不支持跨零点");
            }
        }
        for (int weekday = 1; weekday <= 7; weekday++) {
            List<AutoOutboundTaskRequest.CallWindow> daily = new ArrayList<>();
            for (AutoOutboundTaskRequest.CallWindow window : windows) {
                if (!Boolean.FALSE.equals(window.getEnabled()) && window.getWeekdays().contains(weekday)) {
                    daily.add(window);
                }
            }
            daily.sort(Comparator.comparing(AutoOutboundTaskRequest.CallWindow::getStartTime));
            for (int index = 1; index < daily.size(); index++) {
                if (daily.get(index).getStartTime().isBefore(daily.get(index - 1).getEndTime())) {
                    throw new ServiceException("同一星期的呼叫时段不能重叠");
                }
            }
        }
    }

    private void apply(OutboundTask task, AutoOutboundTaskRequest request) {
        task.setTaskCode(request.getTaskCode().trim());
        task.setTaskName(request.getTaskName().trim());
        task.setDescription(trim(request.getDescription()));
        task.setNodeId(request.getNodeId());
        task.setCallerNumberId(request.getCallerNumberId());
        task.setOutboundLinePolicyId(request.getOutboundLinePolicyId());
        task.setDialMode(request.getDialMode());
        task.setTargetType(request.getTargetType());
        task.setTargetId(request.getTargetId());
        task.setSkillGroupId(request.getSkillGroupId());
        task.setConcurrencyLimit(request.getConcurrencyLimit());
        task.setCallsPerMinute(request.getCallsPerMinute());
        task.setMaxCallsPerDay(request.getMaxCallsPerDay());
        task.setMaxCallsTotal(request.getMaxCallsTotal());
        task.setMinCallIntervalMinutes(request.getMinCallIntervalMinutes());
        task.setScheduleTimezone(request.getScheduleTimezone());
        task.setResultWritebackEnabled(!Boolean.FALSE.equals(request.getResultWritebackEnabled()));
        task.setConnectedTag(trim(request.getConnectedTag()));
        task.setFailedTag(trim(request.getFailedTag()));
    }

    private void replacePolicies(Long taskId, AutoOutboundTaskRequest request) {
        callWindowMapper.delete(new LambdaQueryWrapper<OutboundTaskCallWindow>()
            .eq(OutboundTaskCallWindow::getTaskId, taskId));
        retryRuleMapper.delete(new LambdaQueryWrapper<OutboundTaskRetryRule>()
            .eq(OutboundTaskRetryRule::getTaskId, taskId));
        for (int index = 0; index < request.getCallWindows().size(); index++) {
            AutoOutboundTaskRequest.CallWindow source = request.getCallWindows().get(index);
            OutboundTaskCallWindow target = new OutboundTaskCallWindow();
            target.setTaskId(taskId);
            target.setWeekdays(source.getWeekdays().stream().distinct().sorted()
                .map(String::valueOf).reduce((left, right) -> left + "," + right).orElse(""));
            target.setStartTime(source.getStartTime());
            target.setEndTime(source.getEndTime());
            target.setEnabled(!Boolean.FALSE.equals(source.getEnabled()));
            target.setSortOrder(index + 1);
            callWindowMapper.insert(target);
        }
        for (int index = 0; index < request.getRetryRules().size(); index++) {
            AutoOutboundTaskRequest.RetryRule source = request.getRetryRules().get(index);
            OutboundTaskRetryRule target = new OutboundTaskRetryRule();
            target.setTaskId(taskId);
            target.setResultCode(source.getResultCode());
            target.setRetryEnabled(!Boolean.FALSE.equals(source.getRetryEnabled()));
            target.setMaxRetryCount(source.getMaxRetryCount());
            target.setRetryIntervalMinutes(source.getRetryIntervalMinutes());
            target.setSortOrder(index + 1);
            retryRuleMapper.insert(target);
        }
    }

    private AutoOutboundTaskResponse toResponse(OutboundTask task) {
        AutoOutboundTaskResponse response = new AutoOutboundTaskResponse();
        response.setId(task.getId());
        response.setTaskCode(task.getTaskCode());
        response.setTaskName(task.getTaskName());
        response.setTaskType(task.getTaskType());
        response.setStatus(task.getStatus());
        response.setDescription(task.getDescription());
        response.setNodeId(task.getNodeId());
        response.setCallerNumberId(task.getCallerNumberId());
        response.setOutboundLinePolicyId(task.getOutboundLinePolicyId());
        response.setDialMode(task.getDialMode());
        response.setTargetType(task.getTargetType());
        response.setTargetId(task.getTargetId());
        response.setSkillGroupId(task.getSkillGroupId());
        response.setConcurrencyLimit(task.getConcurrencyLimit());
        response.setCallsPerMinute(task.getCallsPerMinute());
        response.setMaxCallsPerDay(task.getMaxCallsPerDay());
        response.setMaxCallsTotal(task.getMaxCallsTotal());
        response.setMinCallIntervalMinutes(task.getMinCallIntervalMinutes());
        response.setScheduleTimezone(task.getScheduleTimezone());
        response.setResultWritebackEnabled(task.getResultWritebackEnabled());
        response.setConnectedTag(task.getConnectedTag());
        response.setFailedTag(task.getFailedTag());
        response.setTotalCount(countMembers(task.getId(), null));
        response.setPendingCount(countMembers(task.getId(), List.of("PENDING", "RETRY", "SCHEDULED")));
        response.setCompletedCount(countMembers(task.getId(), List.of("COMPLETED", "SKIPPED", "BLOCKED")));
        response.setActiveCount(countMembers(task.getId(), List.of("SCHEDULED", "CLAIMED", "DIALING")));
        response.setLastScheduledAt(task.getLastScheduledAt());
        response.setLastScheduleSummary(task.getLastScheduleSummary());
        response.setVersion(task.getVersion());
        response.setCreateTime(task.getCreateTime());
        response.setCallWindows(callWindowMapper.selectList(new LambdaQueryWrapper<OutboundTaskCallWindow>()
                .eq(OutboundTaskCallWindow::getTaskId, task.getId())
                .orderByAsc(OutboundTaskCallWindow::getSortOrder))
            .stream().map(this::toCallWindow).toList());
        response.setRetryRules(retryRuleMapper.selectList(new LambdaQueryWrapper<OutboundTaskRetryRule>()
                .eq(OutboundTaskRetryRule::getTaskId, task.getId())
                .orderByAsc(OutboundTaskRetryRule::getSortOrder))
            .stream().map(this::toRetryRule).toList());
        return response;
    }

    private AutoOutboundTaskResponse.CallWindow toCallWindow(OutboundTaskCallWindow source) {
        AutoOutboundTaskResponse.CallWindow target = new AutoOutboundTaskResponse.CallWindow();
        target.setId(source.getId());
        target.setWeekdays(Arrays.stream(source.getWeekdays().split(","))
            .filter(value -> !value.isBlank()).map(Integer::valueOf).toList());
        target.setStartTime(source.getStartTime());
        target.setEndTime(source.getEndTime());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        return target;
    }

    private AutoOutboundTaskResponse.RetryRule toRetryRule(OutboundTaskRetryRule source) {
        AutoOutboundTaskResponse.RetryRule target = new AutoOutboundTaskResponse.RetryRule();
        target.setId(source.getId());
        target.setResultCode(source.getResultCode());
        target.setRetryEnabled(source.getRetryEnabled());
        target.setMaxRetryCount(source.getMaxRetryCount());
        target.setRetryIntervalMinutes(source.getRetryIntervalMinutes());
        target.setSortOrder(source.getSortOrder());
        return target;
    }

    private OutboundTask requireTask(Long id) {
        OutboundTask task = taskMapper.selectOne(new LambdaQueryWrapper<OutboundTask>()
            .eq(OutboundTask::getId, id).eq(OutboundTask::getTaskType, TASK_TYPE));
        if (task == null) {
            throw new ServiceException("自动外呼任务不存在");
        }
        return task;
    }

    private long countMembers(Long taskId, List<String> statuses) {
        LambdaQueryWrapper<OutboundMember> query = new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId);
        if (statuses != null) {
            query.in(OutboundMember::getStatus, statuses);
        }
        return memberMapper.selectCount(query);
    }

    private boolean hasActiveCalls(Long taskId) {
        return dispatchMapper.selectCount(new LambdaQueryWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getTaskId, taskId)
            .in(AutoOutboundDispatch::getStatus, "READY", "PROCESSING")) > 0
            || countMembers(taskId, List.of("SCHEDULED", "DIALING")) > 0;
    }

    private void cancelReadyDispatches(Long taskId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        dispatchMapper.update(null, new LambdaUpdateWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getTaskId, taskId)
            .eq(AutoOutboundDispatch::getStatus, "READY")
            .set(AutoOutboundDispatch::getStatus, "CANCELLED")
            .set(AutoOutboundDispatch::getCompletedAt, now)
            .set(AutoOutboundDispatch::getFailureReason, reason)
            .set(AutoOutboundDispatch::getLeaseOwner, null)
            .set(AutoOutboundDispatch::getLeaseExpiresAt, null));
        memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .eq(OutboundMember::getStatus, "SCHEDULED")
            .set(OutboundMember::getStatus, "PENDING")
            .set(OutboundMember::getScheduleKey, null)
            .set(OutboundMember::getScheduledAt, null)
            .set(OutboundMember::getLeaseExpiresAt, null));
    }

    private void updateStatus(Long id, String status) {
        taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
            .eq(OutboundTask::getId, id)
            .eq(OutboundTask::getTaskType, TASK_TYPE)
            .set(OutboundTask::getStatus, status));
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
