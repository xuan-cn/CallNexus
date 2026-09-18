package org.dromara.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.quality.domain.*;
import org.dromara.quality.domain.request.*;
import org.dromara.quality.domain.response.*;
import org.dromara.quality.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class QualityAppealService {
    private static final int APPEAL_DAYS = 7;

    private final QualityAppealMapper appealMapper;
    private final QualityTaskMapper taskMapper;
    private final QualityResultMapper resultMapper;
    private final QualityItemResultMapper itemResultMapper;
    private final QualityAuditLogMapper auditLogMapper;
    private final QualityTaskService taskService;
    private final QualityDataScopeService dataScopeService;

    public TableDataInfo<QualityMyResultResponse> myResults(QualityTaskPageQuery query, PageQuery pageQuery) {
        Long agentId = taskService.currentAgentId();
        LambdaQueryWrapper<QualityTask> wrapper = new LambdaQueryWrapper<QualityTask>()
            .eq(QualityTask::getAgentId, agentId)
            .isNotNull(QualityTask::getPublishedAt)
            .and(StringUtils.isNotBlank(query.getKeyword()), w -> w
                .like(QualityTask::getTaskCode, query.getKeyword()).or()
                .like(QualityTask::getBusinessCallId, query.getKeyword()))
            .orderByDesc(QualityTask::getPublishedAt);
        Page<QualityTask> page = taskMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::myResultResponse).toList(), page.getTotal());
    }

    public QualityTaskDetailResponse myResultDetail(Long taskId) {
        return taskService.getForCurrentAgent(taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(Long taskId, QualityAppealCreateRequest request) {
        QualityTask task = taskService.require(taskId);
        if (!Objects.equals(task.getAgentId(), taskService.currentAgentId())) throw new ServiceException("只能申诉本人的质检结果");
        if (task.getPublishedAt() == null) throw new ServiceException("质检结果尚未发布，不能申诉");
        LocalDateTime deadline = task.getPublishedAt().plusDays(APPEAL_DAYS);
        if (LocalDateTime.now().isAfter(deadline)) throw new ServiceException("申诉期限已过，质检结果发布后7天内可以申诉");
        QualityAppeal active = latestAppeal(taskId);
        if (active != null && Set.of("SUBMITTED", "RECHECK_REQUESTED").contains(active.getStatus())) {
            throw new ServiceException("该质检结果已有待处理申诉");
        }
        QualityResultResponse result = taskService.currentResult(taskId);
        if (result == null) throw new ServiceException("未找到有效质检结果");
        QualityAppeal appeal = new QualityAppeal();
        appeal.setTaskId(taskId); appeal.setQualityResultId(result.getId()); appeal.setAppealReason(request.getAppealReason().trim());
        appeal.setAttachmentOssIds(StringUtils.blankToDefault(request.getAttachmentOssIds(), null)); appeal.setStatus("SUBMITTED");
        appeal.setAppellantId(LoginHelper.getUserId()); appeal.setAppellantName(LoginHelper.getUsername());
        appeal.setAppealedAt(LocalDateTime.now()); appeal.setAppealDeadline(deadline); appealMapper.insert(appeal);
        QualityTask before = snapshot(task); task.setStatus("APPEALED"); taskMapper.updateById(task);
        audit(taskId, "APPEAL_SUBMITTED", before, appeal, "坐席提交质检申诉");
        return appeal.getId();
    }

    public TableDataInfo<QualityAppealResponse> page(QualityAppealPageQuery query, PageQuery pageQuery) {
        QualityDataScopeService.Scope scope = dataScopeService.current();
        if (scope.restricted() && scope.agentIds().isEmpty() && scope.queueIds().isEmpty()) {
            return new TableDataInfo<>(List.of(), 0);
        }
        Page<QualityAppeal> page = appealMapper.selectScopedPage(pageQuery.build(), TenantHelper.getTenantId(), query,
            scope.restricted(), !scope.agentIds().isEmpty(), !scope.queueIds().isEmpty(), scope.agentIds(), scope.queueIds());
        return new TableDataInfo<>(page.getRecords().stream().map(this::response).toList(), page.getTotal());
    }

    public QualityAppealDetailResponse get(Long id) {
        QualityAppeal appeal = require(id);
        QualityAppealDetailResponse value = new QualityAppealDetailResponse();
        copy(response(appeal), value);
        value.setTask(taskService.get(appeal.getTaskId()));
        value.setOriginalResult(result(appeal.getQualityResultId()));
        value.setReviewResult(result(appeal.getReviewResultId()));
        return value;
    }

    public List<QualityCalibrationResponse> calibration() {
        QualityDataScopeService.Scope scope = dataScopeService.current();
        if (scope.restricted() && scope.agentIds().isEmpty() && scope.queueIds().isEmpty()) return List.of();
        return appealMapper.selectCalibration(TenantHelper.getTenantId(), scope.restricted(),
            !scope.agentIds().isEmpty(), !scope.queueIds().isEmpty(), scope.agentIds(), scope.queueIds());
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, QualityAppealDecisionRequest request) {
        QualityAppeal appeal = requirePending(id);
        finish(appeal, "REJECTED", request.getReviewConclusion(), null);
        QualityTask task = taskService.require(appeal.getTaskId()); task.setStatus("PUBLISHED"); taskMapper.updateById(task);
        audit(task.getId(), "APPEAL_REJECTED", null, appeal, request.getReviewConclusion());
    }

    @Transactional(rollbackFor = Exception.class)
    public void recheck(Long id, QualityAppealDecisionRequest request) {
        QualityAppeal appeal = requirePending(id);
        appeal.setStatus("RECHECK_REQUESTED"); appeal.setReviewerId(LoginHelper.getUserId()); appeal.setReviewerName(LoginHelper.getUsername());
        appeal.setReviewedAt(LocalDateTime.now()); appeal.setReviewConclusion(request.getReviewConclusion()); appealMapper.updateById(appeal);
        QualityTask task = taskService.require(appeal.getTaskId());
        task.setReviewerId(LoginHelper.getUserId()); task.setReviewerName(LoginHelper.getUsername());
        task.setAssignedAt(LocalDateTime.now()); task.setStatus("ASSIGNED"); taskMapper.updateById(task);
        audit(task.getId(), "APPEAL_RECHECK_REQUESTED", null, appeal, request.getReviewConclusion());
    }

    @Transactional(rollbackFor = Exception.class)
    public Long accept(Long id, QualityAppealAcceptRequest request) {
        QualityAppeal appeal = requirePending(id);
        Long resultId = taskService.reviewAppeal(appeal.getTaskId(), request);
        finish(appeal, "ACCEPTED", request.getReviewConclusion(), resultId);
        return resultId;
    }

    private void finish(QualityAppeal appeal, String status, String conclusion, Long resultId) {
        appeal.setStatus(status); appeal.setReviewerId(LoginHelper.getUserId()); appeal.setReviewerName(LoginHelper.getUsername());
        appeal.setReviewedAt(LocalDateTime.now()); appeal.setReviewConclusion(conclusion); appeal.setReviewResultId(resultId);
        appealMapper.updateById(appeal);
    }

    private QualityAppeal require(Long id) {
        QualityAppeal value = appealMapper.selectById(id);
        if (value == null) throw new ServiceException("质检申诉不存在");
        taskService.requireAccessible(value.getTaskId());
        return value;
    }

    private QualityAppeal requirePending(Long id) {
        QualityAppeal value = require(id);
        if (!"SUBMITTED".equals(value.getStatus())) throw new ServiceException("当前申诉状态不能复核");
        return value;
    }

    private QualityAppeal latestAppeal(Long taskId) {
        return appealMapper.selectOne(new LambdaQueryWrapper<QualityAppeal>().eq(QualityAppeal::getTaskId, taskId)
            .orderByDesc(QualityAppeal::getAppealedAt).last("LIMIT 1"));
    }

    private QualityMyResultResponse myResultResponse(QualityTask task) {
        QualityTaskResponse base = taskService.response(task); QualityMyResultResponse value = new QualityMyResultResponse(); copyTask(base, value);
        QualityAppeal appeal = latestAppeal(task.getId()); LocalDateTime deadline = task.getPublishedAt().plusDays(APPEAL_DAYS);
        value.setAppealDeadline(deadline); value.setCanAppeal(LocalDateTime.now().isBefore(deadline)
            && (appeal == null || !Set.of("SUBMITTED", "RECHECK_REQUESTED").contains(appeal.getStatus())));
        if (appeal != null) { value.setAppealId(appeal.getId()); value.setAppealStatus(appeal.getStatus()); }
        return value;
    }

    private QualityAppealResponse response(QualityAppeal appeal) {
        QualityAppealResponse value = new QualityAppealResponse(); QualityTask task = taskService.requireAccessible(appeal.getTaskId());
        value.setId(appeal.getId()); value.setTaskId(appeal.getTaskId()); value.setTaskCode(task.getTaskCode()); value.setBusinessCallId(task.getBusinessCallId());
        value.setQualityResultId(appeal.getQualityResultId()); QualityResult original = resultMapper.selectById(appeal.getQualityResultId());
        if (original != null) { value.setOriginalScore(original.getTotalScore()); value.setOriginalQualified(original.getQualified()); }
        value.setAppealReason(appeal.getAppealReason()); value.setAttachmentOssIds(appeal.getAttachmentOssIds()); value.setStatus(appeal.getStatus());
        value.setAppellantId(appeal.getAppellantId()); value.setAppellantName(appeal.getAppellantName()); value.setAppealedAt(appeal.getAppealedAt());
        value.setAppealDeadline(appeal.getAppealDeadline()); value.setReviewerId(appeal.getReviewerId()); value.setReviewerName(appeal.getReviewerName());
        value.setReviewedAt(appeal.getReviewedAt()); value.setReviewConclusion(appeal.getReviewConclusion()); value.setReviewResultId(appeal.getReviewResultId());
        QualityResult review = appeal.getReviewResultId() == null ? null : resultMapper.selectById(appeal.getReviewResultId());
        if (review != null) { value.setReviewScore(review.getTotalScore()); value.setReviewQualified(review.getQualified()); }
        value.setVersion(appeal.getVersion()); return value;
    }

    private QualityResultResponse result(Long id) {
        if (id == null) return null; QualityResult value = resultMapper.selectById(id);
        return value == null ? null : QualityResultResponseMapper.toResponse(value, itemResultMapper);
    }

    private void audit(Long taskId, String type, Object before, Object after, String remark) {
        QualityAuditLog log = new QualityAuditLog(); log.setTaskId(taskId); log.setOperationType(type);
        log.setBeforeJson(before == null ? null : JsonUtils.toJsonString(before)); log.setAfterJson(after == null ? null : JsonUtils.toJsonString(after));
        log.setOperatorId(LoginHelper.getUserId()); log.setOperatorName(LoginHelper.getUsername()); log.setOperationTime(LocalDateTime.now());
        log.setRemark(remark); auditLogMapper.insert(log);
    }

    private QualityTask snapshot(QualityTask task) { return JsonUtils.parseObject(JsonUtils.toJsonString(task), QualityTask.class); }

    private void copy(QualityAppealResponse source, QualityAppealResponse target) {
        try { org.springframework.beans.BeanUtils.copyProperties(source, target); } catch (Exception e) { throw new ServiceException("复制申诉数据失败"); }
    }

    private void copyTask(QualityTaskResponse source, QualityTaskResponse target) {
        org.springframework.beans.BeanUtils.copyProperties(source, target);
    }
}
