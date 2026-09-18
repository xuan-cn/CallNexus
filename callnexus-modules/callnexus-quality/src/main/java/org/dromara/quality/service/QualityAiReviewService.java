package org.dromara.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.response.AiCallTranscriptResponse;
import org.dromara.ai.domain.response.AiCallTranscriptSegmentResponse;
import org.dromara.ai.quality.AiQualityReviewModelService;
import org.dromara.ai.quality.AiQualityReviewRequest;
import org.dromara.ai.quality.AiQualityReviewResult;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.quality.domain.*;
import org.dromara.quality.domain.response.QualityResultResponse;
import org.dromara.quality.mapper.*;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QualityAiReviewService {
    private final QualityAiReviewTaskMapper aiTaskMapper;
    private final QualityTaskMapper taskMapper;
    private final QualityResultMapper resultMapper;
    private final QualityItemResultMapper itemResultMapper;
    private final QualityAuditLogMapper auditLogMapper;
    private final QualityTemplateService templateService;
    private final AiSpeechApplicationService aiSpeechService;
    private final AiQualityReviewModelService modelService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final PlatformTransactionManager transactionManager;
    @Resource(name = "qualityAiReviewExecutor")
    private Executor executor;
    private final String leaseOwner = UUID.randomUUID().toString();

    @PostConstruct
    public void scheduleRecovery() {
        scheduledExecutorService.scheduleWithFixedDelay(this::scanSafely, 15, 15, TimeUnit.SECONDS);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long enqueue(Long qualityTaskId, boolean force) {
        QualityTask qualityTask = requireTask(qualityTaskId);
        QualityTemplate template = templateService.require(qualityTask.getTemplateId());
        if (!Boolean.TRUE.equals(template.getAiReviewEnabled())) throw new ServiceException("当前评分模板未启用 AI 初审");
        QualityAiReviewTask latest = latestTask(qualityTaskId);
        if (!force && latest != null && Set.of("PENDING", "PROCESSING", "RETRY", "SUCCESS").contains(latest.getStatus())) {
            return latest.getId();
        }
        if (latest != null && Set.of("PENDING", "PROCESSING").contains(latest.getStatus())) {
            throw new ServiceException("AI 初审任务正在执行，请稍后刷新");
        }
        if (force && latest != null && "RETRY".equals(latest.getStatus())) {
            latest.setStatus("CANCELLED");
            latest.setFinishedAt(LocalDateTime.now());
            latest.setNextRetryAt(null);
            aiTaskMapper.updateById(latest);
        }
        QualityAiReviewTask task = new QualityAiReviewTask();
        task.setQualityTaskId(qualityTaskId);
        task.setStatus("PENDING");
        task.setRetryCount(0);
        aiTaskMapper.insert(task);
        dispatchAfterCommit(task.getId(), TenantHelper.getTenantId());
        return task.getId();
    }

    public void enqueueIfEnabled(QualityTask task) {
        QualityTemplate template = templateService.require(task.getTemplateId());
        if (Boolean.TRUE.equals(template.getAiReviewEnabled())) enqueue(task.getId(), false);
    }

    public QualityAiReviewTask latestTask(Long qualityTaskId) {
        return aiTaskMapper.selectOne(new LambdaQueryWrapper<QualityAiReviewTask>()
            .eq(QualityAiReviewTask::getQualityTaskId, qualityTaskId)
            .orderByDesc(QualityAiReviewTask::getCreateTime).last("LIMIT 1"));
    }

    public QualityResultResponse latestAiResult(Long qualityTaskId) {
        QualityResult result = resultMapper.selectOne(new LambdaQueryWrapper<QualityResult>()
            .eq(QualityResult::getTaskId, qualityTaskId).eq(QualityResult::getSource, "AI")
            .orderByDesc(QualityResult::getCreateTime).last("LIMIT 1"));
        return result == null ? null : QualityResultResponseMapper.toResponse(result, itemResultMapper);
    }

    private void dispatchAfterCommit(Long id, String tenantId) {
        Runnable action = () -> tryDispatch(id, tenantId);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private void scanSafely() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<QualityAiReviewTask> tasks = TenantHelper.ignore(() -> aiTaskMapper.selectList(
                new LambdaQueryWrapper<QualityAiReviewTask>()
                    .and(value -> value.eq(QualityAiReviewTask::getStatus, "PENDING")
                        .or(retry -> retry.eq(QualityAiReviewTask::getStatus, "RETRY").le(QualityAiReviewTask::getNextRetryAt, now))
                        .or(processing -> processing.eq(QualityAiReviewTask::getStatus, "PROCESSING")
                            .le(QualityAiReviewTask::getLeaseExpiresAt, now)))
                    .orderByAsc(QualityAiReviewTask::getCreateTime).last("LIMIT 20")));
            tasks.forEach(task -> tryDispatch(task.getId(), task.getTenantId()));
        } catch (Exception exception) {
            log.error("扫描 AI 质检初审任务失败", exception);
        }
    }

    private void tryDispatch(Long id, String tenantId) {
        boolean claimed;
        try {
            claimed = TenantHelper.dynamic(tenantId, () -> claim(id));
        } catch (Exception exception) {
            log.warn("抢占 AI 质检初审任务失败，taskId={}", id, exception);
            return;
        }
        if (!claimed) return;
        try {
            executor.execute(() -> TenantHelper.dynamic(tenantId, () -> processClaimed(id)));
        } catch (TaskRejectedException exception) {
            TenantHelper.dynamic(tenantId, () -> releaseClaim(id));
            log.warn("AI 质检执行队列已满，任务释放回持久化队列，taskId={}", id);
        }
    }

    private void processClaimed(Long id) {
        QualityAiReviewTask asyncTask = aiTaskMapper.selectById(id);
        if (asyncTask == null || !"PROCESSING".equals(asyncTask.getStatus()) || !leaseOwner.equals(asyncTask.getLeaseOwner())) return;
        try {
            QualityTask task = requireTask(asyncTask.getQualityTaskId());
            QualityTemplate template = templateService.require(task.getTemplateId());
            List<QualityTemplateItem> templateItems = templateService.versionItems(task.getTemplateId(), task.getTemplateVersionId());
            List<QualityTemplateItem> aiItems = templateItems.stream().filter(item -> !Boolean.FALSE.equals(item.getAiReviewEnabled())).toList();
            if (aiItems.isEmpty()) throw new ServiceException("评分模板没有启用 AI 初审的评分项");
            AiCallTranscriptResponse transcript = aiSpeechService.callTranscript(task.getCallSessionId());
            List<AiCallTranscriptSegmentResponse> segments = Optional.ofNullable(transcript)
                .map(AiCallTranscriptResponse::getSegments).orElseGet(List::of).stream()
                .filter(item -> !Boolean.FALSE.equals(item.getFinalResult()) && StringUtils.isNotBlank(item.getTextContent()))
                .sorted(Comparator.comparing(AiCallTranscriptSegmentResponse::getStartMs, Comparator.nullsLast(Integer::compareTo)))
                .toList();
            AiQualityReviewRequest request = buildRequest(template, aiItems, segments);
            asyncTask.setRequestJson(JsonUtils.toJsonString(request));
            aiTaskMapper.updateById(asyncTask);
            AiQualityReviewResult output = modelService.review(request);
            QualityResult result = new TransactionTemplate(transactionManager).execute(status ->
                persistResult(task, template, templateItems, output));
            if (result == null) throw new ServiceException("AI 初审结果保存失败");
            asyncTask.setModelId(output.modelId());
            asyncTask.setResponseJson(output.rawResponse());
            asyncTask.setStatus("SUCCESS");
            asyncTask.setFinishedAt(LocalDateTime.now());
            asyncTask.setLeaseOwner(null);
            asyncTask.setLeaseExpiresAt(null);
            asyncTask.setNextRetryAt(null);
            asyncTask.setErrorMessage(null);
            aiTaskMapper.updateById(asyncTask);
            audit(task.getId(), "AI_REVIEW_COMPLETED", result, "AI 初审完成");
        } catch (Exception exception) {
            fail(asyncTask, exception);
        }
    }

    private AiQualityReviewRequest buildRequest(QualityTemplate template, List<QualityTemplateItem> items,
                                                List<AiCallTranscriptSegmentResponse> segments) {
        return new AiQualityReviewRequest(template.getTotalScore(), template.getQualifiedScore(), items.stream()
            .map(item -> new AiQualityReviewRequest.Item(item.getItemCode(), item.getItemName(), item.getItemType(),
                item.getScoreValue(), item.getFatalFlag(), item.getAllowNotApplicable(), item.getRuleDescription(), item.getAiPromptHint()))
            .toList(), segments.stream().map(segment -> new AiQualityReviewRequest.Segment(segment.getId(), segment.getSpeaker(),
                segment.getStartMs(), segment.getEndMs(), segment.getTextContent())).toList());
    }

    private QualityResult persistResult(QualityTask task, QualityTemplate template,
                                        List<QualityTemplateItem> templateItems, AiQualityReviewResult output) {
        Map<String, AiQualityReviewResult.Item> outputMap = output.items().stream()
            .collect(Collectors.toMap(AiQualityReviewResult.Item::itemCode, Function.identity(), (left, right) -> left));
        int score = template.getTotalScore() - templateItems.stream().filter(item -> "AWARD".equals(item.getItemType()))
            .mapToInt(item -> Math.abs(Optional.ofNullable(item.getScoreValue()).orElse(0))).sum();
        boolean fatal = false;
        Map<Long, AiQualityReviewResult.Item> normalized = new LinkedHashMap<>();
        for (QualityTemplateItem item : templateItems) {
            AiQualityReviewResult.Item review = outputMap.get(item.getItemCode());
            BigDecimal threshold = Optional.ofNullable(item.getAiConfidenceThreshold()).orElse(new BigDecimal("0.700"));
            if (review == null || Boolean.FALSE.equals(item.getAiReviewEnabled()) || review.confidence().compareTo(threshold) < 0) {
                review = new AiQualityReviewResult.Item(item.getItemCode(), "NEEDS_MANUAL_REVIEW",
                    review == null ? BigDecimal.ZERO : review.confidence(), review == null ? "该评分项未由 AI 分析" : "置信度低于阈值，需要人工判断",
                    review == null ? List.of() : review.evidence());
            }
            if ("FAILED".equals(review.result()) && Boolean.TRUE.equals(item.getEvidenceRequired())
                && (review.evidence() == null || review.evidence().isEmpty())) {
                review = new AiQualityReviewResult.Item(item.getItemCode(), "NEEDS_MANUAL_REVIEW", review.confidence(),
                    "该评分项要求提供证据，但 AI 未返回有效证据", List.of());
            }
            int change = calculateScoreChange(item, review.result());
            score += change;
            fatal |= "FAILED".equals(review.result()) && Boolean.TRUE.equals(item.getFatalFlag());
            normalized.put(item.getId(), review);
        }
        score = Math.max(0, Math.min(template.getTotalScore(), score));
        int version = Math.toIntExact(resultMapper.selectCount(new LambdaQueryWrapper<QualityResult>()
            .eq(QualityResult::getTaskId, task.getId()))) + 1;
        QualityResult result = new QualityResult();
        result.setTaskId(task.getId()); result.setResultVersion(version); result.setTotalScore(score);
        result.setFatalFlag(fatal); result.setQualified(!fatal && score >= template.getQualifiedScore());
        result.setSummary(output.summary()); result.setImprovementSuggestion(output.improvementSuggestion());
        result.setSource("AI"); result.setAiModelId(output.modelId()); result.setRawResponseJson(output.rawResponse());
        result.setEffectiveFlag(false); resultMapper.insert(result);
        for (QualityTemplateItem item : templateItems) {
            AiQualityReviewResult.Item review = normalized.get(item.getId());
            QualityItemResult row = new QualityItemResult();
            row.setQualityResultId(result.getId()); row.setTemplateItemId(item.getId()); row.setItemCode(item.getItemCode());
            row.setItemName(item.getItemName()); row.setResult(review.result()); row.setScoreChange(calculateScoreChange(item, review.result()));
            row.setConfidence(review.confidence()); row.setReason(review.reason()); row.setEvidenceJson(JsonUtils.toJsonString(review.evidence()));
            row.setManuallyModified(false); itemResultMapper.insert(row);
        }
        return result;
    }

    private int calculateScoreChange(QualityTemplateItem item, String result) {
        if ("NOT_APPLICABLE".equals(result) || "NEEDS_MANUAL_REVIEW".equals(result)) return 0;
        int absolute = Math.abs(Optional.ofNullable(item.getScoreValue()).orElse(0));
        if ("AWARD".equals(item.getItemType())) return "PASSED".equals(result) ? absolute : 0;
        if ("DEDUCTION".equals(item.getItemType())) return "FAILED".equals(result) ? -absolute : 0;
        return 0;
    }

    private boolean claim(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return aiTaskMapper.update(null, new LambdaUpdateWrapper<QualityAiReviewTask>()
            .eq(QualityAiReviewTask::getId, id)
            .and(value -> value.eq(QualityAiReviewTask::getStatus, "PENDING")
                .or(retry -> retry.eq(QualityAiReviewTask::getStatus, "RETRY").le(QualityAiReviewTask::getNextRetryAt, now))
                .or(processing -> processing.eq(QualityAiReviewTask::getStatus, "PROCESSING")
                    .le(QualityAiReviewTask::getLeaseExpiresAt, now)))
            .set(QualityAiReviewTask::getStatus, "PROCESSING")
            .set(QualityAiReviewTask::getStartedAt, now)
            .set(QualityAiReviewTask::getLeaseOwner, leaseOwner)
            .set(QualityAiReviewTask::getLeaseExpiresAt, now.plusMinutes(2))
            .set(QualityAiReviewTask::getErrorMessage, null)) == 1;
    }

    private void releaseClaim(Long id) {
        aiTaskMapper.update(null, new LambdaUpdateWrapper<QualityAiReviewTask>()
            .eq(QualityAiReviewTask::getId, id)
            .eq(QualityAiReviewTask::getStatus, "PROCESSING")
            .eq(QualityAiReviewTask::getLeaseOwner, leaseOwner)
            .set(QualityAiReviewTask::getStatus, "PENDING")
            .set(QualityAiReviewTask::getLeaseOwner, null)
            .set(QualityAiReviewTask::getLeaseExpiresAt, null)
            .set(QualityAiReviewTask::getStartedAt, null));
    }

    private void fail(QualityAiReviewTask task, Exception exception) {
        int retry = Optional.ofNullable(task.getRetryCount()).orElse(0) + 1;
        task.setRetryCount(retry);
        task.setStatus(retry >= 3 ? "FAILED" : "RETRY");
        task.setNextRetryAt(retry >= 3 ? null : LocalDateTime.now().plusMinutes(retry));
        task.setErrorMessage(StringUtils.substring(Optional.ofNullable(exception.getMessage()).orElse("未知错误"), 0, 2000));
        task.setFinishedAt(retry >= 3 ? LocalDateTime.now() : null);
        task.setLeaseOwner(null); task.setLeaseExpiresAt(null);
        aiTaskMapper.updateById(task);
        log.warn("AI 质检初审失败，taskId={}，retry={}", task.getQualityTaskId(), retry, exception);
    }

    private QualityTask requireTask(Long id) {
        QualityTask task = taskMapper.selectById(id);
        if (task == null) throw new ServiceException("质检任务不存在");
        return task;
    }

    private void audit(Long taskId, String type, Object after, String remark) {
        QualityAuditLog log = new QualityAuditLog();
        log.setTaskId(taskId); log.setOperationType(type); log.setAfterJson(JsonUtils.toJsonString(after));
        log.setOperatorName("SYSTEM"); log.setOperationTime(LocalDateTime.now()); log.setRemark(remark);
        auditLogMapper.insert(log);
    }
}
