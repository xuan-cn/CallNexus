package org.dromara.quality.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.response.AiCallTranscriptResponse;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.call.domain.request.CallRecordPageQuery;
import org.dromara.call.domain.response.CallRecordResponse;
import org.dromara.call.service.CallRecordApplicationService;
import org.dromara.common.core.exception.ServiceException;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QualityTaskService {
    private final QualityTaskMapper taskMapper;
    private final QualityResultMapper resultMapper;
    private final QualityItemResultMapper itemResultMapper;
    private final QualityAuditLogMapper auditLogMapper;
    private final QualityAppealMapper appealMapper;
    private final QualityTemplateService templateService;
    private final CallRecordApplicationService callRecordService;
    private final AiSpeechApplicationService aiSpeechService;
    private final QualityAiReviewService aiReviewService;
    private final QualityDataScopeService dataScopeService;

    public TableDataInfo<QualityTaskResponse> page(QualityTaskPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<QualityTask> wrapper = new LambdaQueryWrapper<QualityTask>()
            .and(org.dromara.common.core.utils.StringUtils.isNotBlank(query.getKeyword()), w -> w
                .like(QualityTask::getTaskCode, query.getKeyword()).or()
                .like(QualityTask::getBusinessCallId, query.getKeyword()).or()
                .like(QualityTask::getAgentName, query.getKeyword()).or()
                .like(QualityTask::getAgentExtension, query.getKeyword()))
            .eq(org.dromara.common.core.utils.StringUtils.isNotBlank(query.getStatus()), QualityTask::getStatus, query.getStatus())
            .eq(query.getReviewerId() != null, QualityTask::getReviewerId, query.getReviewerId())
            .eq(query.getAgentId() != null, QualityTask::getAgentId, query.getAgentId())
            .eq(query.getTemplateId() != null, QualityTask::getTemplateId, query.getTemplateId())
            .eq(Boolean.TRUE.equals(query.getMine()), QualityTask::getReviewerId, LoginHelper.getUserId())
            .ge(query.getCreatedAtFrom() != null, QualityTask::getCreateTime, query.getCreatedAtFrom())
            .le(query.getCreatedAtTo() != null, QualityTask::getCreateTime, query.getCreatedAtTo())
            .orderByAsc(QualityTask::getPriority).orderByDesc(QualityTask::getCreateTime);
        dataScopeService.applyTaskScope(wrapper, dataScopeService.current());
        Page<QualityTask> page = taskMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::response).toList(), page.getTotal());
    }

    public TableDataInfo<CallRecordResponse> callCandidates(CallRecordPageQuery query, PageQuery pageQuery) {
        query.setCallStatus("ENDED");
        dataScopeService.applyCallScope(query, dataScopeService.current());
        return callRecordService.page(query, pageQuery);
    }

    public List<QualityUserOptionResponse> reviewerOptions() {
        return taskMapper.selectReviewerOptions(TenantHelper.getTenantId());
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Long> createManual(ManualQualityTaskCreateRequest request) {
        QualityTemplate template = templateService.requirePublished(request.getTemplateId());
        String reviewerName = request.getReviewerId() == null ? null : requireUserName(request.getReviewerId());
        QualityDataScopeService.Scope scope = dataScopeService.current();
        List<Long> ids = new ArrayList<>();
        for (Long callSessionId : request.getCallSessionIds().stream().distinct().toList()) {
            if (taskMapper.exists(new LambdaQueryWrapper<QualityTask>()
                .eq(QualityTask::getCallSessionId, callSessionId)
                .eq(QualityTask::getTemplateVersionId, template.getCurrentVersionId()))) {
                throw new ServiceException("通话 " + callSessionId + " 已按当前模板版本生成质检任务");
            }
            CallRecordResponse call = callRecordService.get(callSessionId);
            dataScopeService.assertAccessible(scope, call.getAgentId(), call.getHandlingQueueId());
            if (!"ENDED".equals(call.getCallStatus())) throw new ServiceException("只能质检已结束的通话：" + callSessionId);
            QualityTask task = new QualityTask();
            task.setTaskCode("QT-" + IdUtil.fastSimpleUUID().substring(0, 12).toUpperCase());
            task.setSource("MANUAL"); task.setCallSessionId(callSessionId); task.setBusinessCallId(call.getBusinessCallId());
            task.setTemplateId(template.getId()); task.setTemplateVersionId(template.getCurrentVersionId());
            task.setAgentId(call.getAgentId()); task.setAgentExtension(call.getAgentExtension());
            task.setAgentName(call.getAgentId() == null ? call.getAgentExtension() : taskMapper.selectAgentName(TenantHelper.getTenantId(), call.getAgentId()));
            task.setQueueId(call.getHandlingQueueId()); task.setQueueName(call.getHandlingQueueName());
            task.setPriority(Optional.ofNullable(request.getPriority()).orElse(5));
            task.setReviewerId(request.getReviewerId()); task.setReviewerName(reviewerName);
            task.setStatus(request.getReviewerId() == null ? "PENDING" : "ASSIGNED");
            if (request.getReviewerId() != null) task.setAssignedAt(LocalDateTime.now());
            taskMapper.insert(task); ids.add(task.getId());
            audit(task.getId(), "CREATED", null, task, "人工抽取通话创建质检任务");
            aiReviewService.enqueueIfEnabled(task);
        }
        return ids;
    }

    public Long createSampled(QualitySamplingCandidate call, QualityTemplate template, Long planId,
                              Long executionId, Long reviewerId, String reviewerName, Integer priority) {
        if (taskMapper.exists(new LambdaQueryWrapper<QualityTask>()
            .eq(QualityTask::getCallSessionId, call.getCallSessionId())
            .eq(QualityTask::getTemplateVersionId, template.getCurrentVersionId()))) {
            return null;
        }
        QualityTask task = new QualityTask();
        task.setTaskCode("QT-" + IdUtil.fastSimpleUUID().substring(0, 12).toUpperCase());
        task.setSource("SAMPLING");
        task.setSamplingPlanId(planId);
        task.setSamplingExecutionId(executionId);
        task.setCallSessionId(call.getCallSessionId());
        task.setBusinessCallId(call.getBusinessCallId());
        task.setTemplateId(template.getId());
        task.setTemplateVersionId(template.getCurrentVersionId());
        task.setAgentId(call.getAgentId());
        task.setAgentName(call.getAgentName());
        task.setAgentExtension(call.getAgentExtension());
        task.setQueueId(call.getQueueId());
        task.setQueueName(call.getQueueName());
        task.setReviewerId(reviewerId);
        task.setReviewerName(reviewerName);
        task.setPriority(Optional.ofNullable(priority).orElse(5));
        task.setStatus(reviewerId == null ? "PENDING" : "ASSIGNED");
        if (reviewerId != null) task.setAssignedAt(LocalDateTime.now());
        taskMapper.insert(task);
        audit(task.getId(), "CREATED", null, task, "抽检计划自动创建质检任务");
        aiReviewService.enqueueIfEnabled(task);
        return task.getId();
    }

    public QualityTaskDetailResponse get(Long id) {
        return detail(requireAccessible(id));
    }

    private QualityTaskDetailResponse detail(QualityTask task) {
        QualityTaskDetailResponse detail = new QualityTaskDetailResponse();
        copyResponse(response(task), detail);
        detail.setCall(callRecordService.get(task.getCallSessionId()));
        try {
            detail.setTranscript(aiSpeechService.callTranscript(task.getCallSessionId()));
        } catch (Exception exception) {
            log.debug("质检任务暂未取得通话转写，taskId={}, callSessionId={}", task.getId(), task.getCallSessionId(), exception);
        }
        detail.setTemplateItems(templateService.versionItems(task.getTemplateId(), task.getTemplateVersionId())
            .stream().map(templateService::itemResponse).toList());
        detail.setResult(currentResult(task.getId()));
        detail.setAiResult(aiReviewService.latestAiResult(task.getId()));
        QualityAiReviewTask aiTask = aiReviewService.latestTask(task.getId());
        if (aiTask != null) {
            detail.setAiReviewStatus(aiTask.getStatus());
            detail.setAiReviewError(aiTask.getErrorMessage());
        }
        return detail;
    }

    public QualityTaskDetailResponse getForCurrentAgent(Long id) {
        QualityTask task = require(id);
        if (!Objects.equals(task.getAgentId(), currentAgentId())) {
            throw new ServiceException("只能查看本人的质检结果");
        }
        if (!Set.of("PUBLISHED", "APPEALED", "ASSIGNED", "SUBMITTED").contains(task.getStatus()) || task.getPublishedAt() == null) {
            throw new ServiceException("质检结果尚未发布");
        }
        return detail(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public void assign(Long id, QualityTaskAssignRequest request) {
        QualityTask task = requireAccessible(id);
        if (!Set.of("PENDING", "ASSIGNED").contains(task.getStatus())) throw new ServiceException("当前任务状态不能重新分配");
        QualityTask before = snapshot(task);
        task.setReviewerId(request.getReviewerId()); task.setReviewerName(requireUserName(request.getReviewerId()));
        task.setAssignedAt(LocalDateTime.now()); task.setStatus("ASSIGNED"); taskMapper.updateById(task);
        audit(id, "ASSIGNED", before, task, "分配质检任务");
    }

    @Transactional(rollbackFor = Exception.class)
    public void claim(Long id) {
        QualityTask task = requireAccessible(id);
        if ("ASSIGNED".equals(task.getStatus()) && LoginHelper.getUserId().equals(task.getReviewerId())) return;
        if (!"PENDING".equals(task.getStatus())) throw new ServiceException("任务已被领取或当前状态不可领取");
        QualityTask before = snapshot(task);
        task.setReviewerId(LoginHelper.getUserId()); task.setReviewerName(LoginHelper.getUsername());
        task.setAssignedAt(LocalDateTime.now()); task.setStatus("ASSIGNED"); taskMapper.updateById(task);
        audit(id, "CLAIMED", before, task, "质检员领取任务");
    }

    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long id, QualityReviewSubmitRequest request) {
        QualityTask task = requireAccessible(id);
        if (!Set.of("ASSIGNED", "REVIEWING", "SUBMITTED").contains(task.getStatus())) throw new ServiceException("当前任务状态不能提交评分");
        if (!LoginHelper.getUserId().equals(task.getReviewerId())) throw new ServiceException("只能提交分配给自己的质检任务");
        List<QualityTemplateItem> templateItems = templateService.versionItems(task.getTemplateId(), task.getTemplateVersionId());
        Map<Long, QualityTemplateItem> itemMap = templateItems.stream().collect(Collectors.toMap(QualityTemplateItem::getId, Function.identity()));
        if (request.getItems().stream().map(QualityItemReviewRequest::getTemplateItemId).distinct().count() != templateItems.size()
            || !request.getItems().stream().allMatch(item -> itemMap.containsKey(item.getTemplateItemId()))) {
            throw new ServiceException("请完成全部评分项后再提交");
        }
        resultMapper.update(null, new LambdaUpdateWrapper<QualityResult>()
            .eq(QualityResult::getTaskId, id).eq(QualityResult::getEffectiveFlag, true)
            .set(QualityResult::getEffectiveFlag, false));
        int resultVersion = Math.toIntExact(resultMapper.selectCount(new LambdaQueryWrapper<QualityResult>().eq(QualityResult::getTaskId, id))) + 1;
        int score = templateService.require(task.getTemplateId()).getTotalScore()
            - templateItems.stream().filter(item -> "AWARD".equals(item.getItemType()))
                .mapToInt(item -> Math.abs(Optional.ofNullable(item.getScoreValue()).orElse(0))).sum();
        boolean fatal = false;
        QualityResultResponse aiResult = aiReviewService.latestAiResult(id);
        Map<Long, QualityItemResultResponse> aiItems = aiResult == null ? Map.of() : aiResult.getItems().stream()
            .collect(Collectors.toMap(QualityItemResultResponse::getTemplateItemId, Function.identity(), (left, right) -> left));
        List<QualityItemReviewRequest> normalized = new ArrayList<>();
        for (QualityItemReviewRequest itemRequest : request.getItems()) {
            QualityTemplateItem item = itemMap.get(itemRequest.getTemplateItemId());
            String result = itemRequest.getResult();
            if (!Set.of("PASSED", "FAILED", "NOT_APPLICABLE").contains(result)) throw new ServiceException("评分结果无效：" + result);
            if ("NOT_APPLICABLE".equals(result) && !Boolean.TRUE.equals(item.getAllowNotApplicable())) throw new ServiceException(item.getItemName() + " 不允许不适用");
            if ("FAILED".equals(result) && org.dromara.common.core.utils.StringUtils.isBlank(itemRequest.getReason())) {
                throw new ServiceException(item.getItemName() + " 判定不通过时必须填写原因");
            }
            if ("FAILED".equals(result) && Boolean.TRUE.equals(item.getEvidenceRequired())
                && org.dromara.common.core.utils.StringUtils.isBlank(itemRequest.getEvidenceJson())) {
                throw new ServiceException(item.getItemName() + " 判定不通过时必须填写证据");
            }
            int change = calculateScoreChange(item, itemRequest);
            itemRequest.setScoreChange(change); score += change;
            QualityItemResultResponse aiItem = aiItems.get(item.getId());
            boolean modified = aiItem != null && !"NEEDS_MANUAL_REVIEW".equals(aiItem.getResult())
                && (!Objects.equals(aiItem.getResult(), itemRequest.getResult())
                    || !Objects.equals(Optional.ofNullable(aiItem.getScoreChange()).orElse(0), change));
            if (modified && org.dromara.common.core.utils.StringUtils.isBlank(itemRequest.getModificationReason())) {
                throw new ServiceException(item.getItemName() + " 修改了 AI 初审结论，请填写修改原因");
            }
            fatal |= "FAILED".equals(result) && Boolean.TRUE.equals(item.getFatalFlag());
            normalized.add(itemRequest);
        }
        QualityTemplate template = templateService.require(task.getTemplateId());
        score = Math.max(0, Math.min(template.getTotalScore(), score));
        QualityResult qualityResult = new QualityResult();
        qualityResult.setTaskId(id); qualityResult.setResultVersion(resultVersion); qualityResult.setTotalScore(score);
        qualityResult.setFatalFlag(fatal); qualityResult.setQualified(!fatal && score >= template.getQualifiedScore());
        qualityResult.setSummary(request.getSummary()); qualityResult.setImprovementSuggestion(request.getImprovementSuggestion());
        qualityResult.setSource("MANUAL"); qualityResult.setEffectiveFlag(true); resultMapper.insert(qualityResult);
        for (QualityItemReviewRequest itemRequest : normalized) {
            QualityTemplateItem item = itemMap.get(itemRequest.getTemplateItemId());
            QualityItemResult value = new QualityItemResult();
            value.setQualityResultId(qualityResult.getId()); value.setTemplateItemId(item.getId()); value.setItemCode(item.getItemCode()); value.setItemName(item.getItemName());
            value.setResult(itemRequest.getResult()); value.setScoreChange(itemRequest.getScoreChange()); value.setReason(itemRequest.getReason());
            QualityItemResultResponse aiItem = aiItems.get(item.getId());
            boolean modified = aiItem != null && !"NEEDS_MANUAL_REVIEW".equals(aiItem.getResult())
                && (!Objects.equals(aiItem.getResult(), itemRequest.getResult())
                    || !Objects.equals(Optional.ofNullable(aiItem.getScoreChange()).orElse(0), itemRequest.getScoreChange()));
            value.setConfidence(aiItem == null ? null : aiItem.getConfidence());
            value.setEvidenceJson(itemRequest.getEvidenceJson()); value.setManuallyModified(modified);
            value.setModificationReason(modified ? itemRequest.getModificationReason() : null); itemResultMapper.insert(value);
        }
        QualityTask before = snapshot(task);
        task.setStatus("SUBMITTED"); task.setSubmittedAt(LocalDateTime.now()); taskMapper.updateById(task);
        audit(id, "SUBMITTED", before, qualityResult, "提交人工质检结果");
        return qualityResult.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void publish(Long id) {
        QualityTask task = requireAccessible(id);
        if (!"SUBMITTED".equals(task.getStatus()) || currentResult(id) == null) throw new ServiceException("任务尚未提交质检结果");
        QualityTask before = snapshot(task);
        task.setStatus("PUBLISHED"); task.setPublishedAt(LocalDateTime.now()); taskMapper.updateById(task);
        audit(id, "PUBLISHED", before, task, "发布质检结果");
        QualityAppeal appeal = appealMapper.selectOne(new LambdaQueryWrapper<QualityAppeal>()
            .eq(QualityAppeal::getTaskId, id).eq(QualityAppeal::getStatus, "RECHECK_REQUESTED")
            .orderByDesc(QualityAppeal::getAppealedAt).last("LIMIT 1"));
        if (appeal != null) {
            QualityResultResponse result = currentResult(id);
            if (result != null) {
                QualityResult recheckResult = resultMapper.selectById(result.getId());
                recheckResult.setSource("RECHECK");
                resultMapper.updateById(recheckResult);
            }
            appeal.setStatus("ACCEPTED");
            appeal.setReviewerId(LoginHelper.getUserId());
            appeal.setReviewerName(LoginHelper.getUsername());
            appeal.setReviewedAt(LocalDateTime.now());
            appeal.setReviewResultId(result == null ? null : result.getId());
            appealMapper.updateById(appeal);
            audit(id, "APPEAL_RECHECK_COMPLETED", null, appeal, "申诉重新质检完成并发布");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Long reviewAppeal(Long id, QualityAppealAcceptRequest request) {
        QualityTask task = requireAccessible(id);
        QualityTask before = snapshot(task);
        Long originalReviewerId = task.getReviewerId();
        String originalReviewerName = task.getReviewerName();
        task.setReviewerId(LoginHelper.getUserId());
        task.setReviewerName(LoginHelper.getUsername());
        task.setAssignedAt(LocalDateTime.now());
        task.setStatus("ASSIGNED");
        taskMapper.updateById(task);
        QualityReviewSubmitRequest submitRequest = new QualityReviewSubmitRequest();
        submitRequest.setSummary(request.getSummary());
        submitRequest.setImprovementSuggestion(request.getImprovementSuggestion());
        submitRequest.setItems(request.getItems());
        Long resultId = submit(id, submitRequest);
        QualityResult result = resultMapper.selectById(resultId);
        result.setSource("RECHECK");
        resultMapper.updateById(result);
        task = requireAccessible(id);
        task.setReviewerId(originalReviewerId);
        task.setReviewerName(originalReviewerName);
        taskMapper.updateById(task);
        publish(id);
        audit(id, "APPEAL_ACCEPTED", before, result, request.getReviewConclusion());
        return resultId;
    }

    private int calculateScoreChange(QualityTemplateItem item, QualityItemReviewRequest request) {
        if ("NOT_APPLICABLE".equals(request.getResult())) return 0;
        if ("MANUAL".equals(item.getItemType())) return Optional.ofNullable(request.getScoreChange()).orElse(0);
        int absolute = Math.abs(Optional.ofNullable(item.getScoreValue()).orElse(0));
        if ("AWARD".equals(item.getItemType())) return "PASSED".equals(request.getResult()) ? absolute : 0;
        return "FAILED".equals(request.getResult()) ? -absolute : 0;
    }

    QualityTask require(Long id) {
        QualityTask value = taskMapper.selectById(id);
        if (value == null) throw new ServiceException("质检任务不存在");
        return value;
    }

    QualityTask requireAccessible(Long id) {
        QualityTask value = require(id);
        dataScopeService.assertAccessible(value.getAgentId(), value.getQueueId());
        return value;
    }

    public Long triggerAiReview(Long id) {
        requireAccessible(id);
        return aiReviewService.enqueue(id, true);
    }

    private String requireUserName(Long userId) {
        String name = taskMapper.selectUserName(TenantHelper.getTenantId(), userId);
        if (name == null) throw new ServiceException("质检员不存在或已停用");
        return name;
    }

    QualityTaskResponse response(QualityTask task) {
        QualityTaskResponse value = new QualityTaskResponse();
        value.setId(task.getId()); value.setTaskCode(task.getTaskCode()); value.setSource(task.getSource());
        value.setSamplingPlanId(task.getSamplingPlanId()); value.setSamplingExecutionId(task.getSamplingExecutionId()); value.setCallSessionId(task.getCallSessionId());
        value.setBusinessCallId(task.getBusinessCallId()); value.setTemplateId(task.getTemplateId()); value.setAgentId(task.getAgentId());
        value.setAgentName(task.getAgentName()); value.setAgentExtension(task.getAgentExtension()); value.setQueueId(task.getQueueId()); value.setQueueName(task.getQueueName());
        value.setReviewerId(task.getReviewerId()); value.setReviewerName(task.getReviewerName()); value.setStatus(task.getStatus()); value.setPriority(task.getPriority());
        value.setAssignedAt(task.getAssignedAt()); value.setSubmittedAt(task.getSubmittedAt()); value.setPublishedAt(task.getPublishedAt());
        value.setCreateTime(task.getCreateTime()); value.setVersion(task.getVersion());
        QualityTemplate template = templateService.require(task.getTemplateId()); value.setTemplateName(template.getTemplateName());
        value.setTemplateVersionNo(templateService.versionNo(task.getTemplateVersionId()));
        value.setTemplateTotalScore(template.getTotalScore()); value.setTemplateQualifiedScore(template.getQualifiedScore());
        value.setAiReviewEnabled(template.getAiReviewEnabled());
        QualityResultResponse result = currentResult(task.getId());
        if (result != null) { value.setTotalScore(result.getTotalScore()); value.setQualified(result.getQualified()); }
        return value;
    }

    QualityResultResponse currentResult(Long taskId) {
        QualityResult result = resultMapper.selectOne(new LambdaQueryWrapper<QualityResult>()
            .eq(QualityResult::getTaskId, taskId).eq(QualityResult::getEffectiveFlag, true).last("LIMIT 1"));
        if (result == null) return null;
        return QualityResultResponseMapper.toResponse(result, itemResultMapper);
    }

    Long currentAgentId() {
        Long agentId = taskMapper.selectAgentIdByUserId(TenantHelper.getTenantId(), LoginHelper.getUserId());
        if (agentId == null) throw new ServiceException("当前用户未绑定启用的坐席，无法查看本人质检结果");
        return agentId;
    }

    private void audit(Long taskId, String type, Object before, Object after, String remark) {
        QualityAuditLog log = new QualityAuditLog();
        log.setTaskId(taskId); log.setOperationType(type); log.setBeforeJson(before == null ? null : JsonUtils.toJsonString(before));
        log.setAfterJson(after == null ? null : JsonUtils.toJsonString(after)); log.setOperatorId(LoginHelper.getUserId());
        log.setOperatorName(LoginHelper.getUsername()); log.setOperationTime(LocalDateTime.now()); log.setRemark(remark); auditLogMapper.insert(log);
    }

    private QualityTask snapshot(QualityTask task) {
        return JsonUtils.parseObject(JsonUtils.toJsonString(task), QualityTask.class);
    }

    private void copyResponse(QualityTaskResponse source, QualityTaskResponse target) {
        target.setId(source.getId()); target.setTaskCode(source.getTaskCode()); target.setSource(source.getSource());
        target.setSamplingPlanId(source.getSamplingPlanId()); target.setSamplingExecutionId(source.getSamplingExecutionId()); target.setCallSessionId(source.getCallSessionId());
        target.setBusinessCallId(source.getBusinessCallId()); target.setTemplateId(source.getTemplateId()); target.setTemplateName(source.getTemplateName());
        target.setTemplateVersionNo(source.getTemplateVersionNo()); target.setTemplateTotalScore(source.getTemplateTotalScore());
        target.setTemplateQualifiedScore(source.getTemplateQualifiedScore()); target.setAiReviewEnabled(source.getAiReviewEnabled());
        target.setAgentId(source.getAgentId()); target.setAgentName(source.getAgentName());
        target.setAgentExtension(source.getAgentExtension()); target.setQueueId(source.getQueueId()); target.setQueueName(source.getQueueName());
        target.setReviewerId(source.getReviewerId()); target.setReviewerName(source.getReviewerName()); target.setStatus(source.getStatus());
        target.setPriority(source.getPriority()); target.setTotalScore(source.getTotalScore()); target.setQualified(source.getQualified());
        target.setAssignedAt(source.getAssignedAt()); target.setSubmittedAt(source.getSubmittedAt()); target.setPublishedAt(source.getPublishedAt());
        target.setCreateTime(source.getCreateTime()); target.setVersion(source.getVersion());
    }
}
