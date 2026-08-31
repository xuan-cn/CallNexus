package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.AiTicketDraftResponse;
import org.dromara.ai.domain.response.AiTicketDraftBatchReviewResponse;
import org.dromara.ai.mapper.*;
import org.dromara.ai.service.*;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AiTicketDraftReviewServiceImpl implements AiTicketDraftReviewService {
    private static final List<String> REVIEWABLE = List.of("PENDING_REVIEW", "LOW_CONFIDENCE");
    private final AiTicketDraftMapper draftMapper;
    private final AiTicketDraftAuditMapper auditMapper;
    private final AiTicketDraftTaskMapper taskMapper;
    private final AiCallTranscriptMapper transcriptMapper;
    private final AiCallRecordingSourceMapper callSourceMapper;
    private final AiTicketDraftTaskDispatcher dispatcher;
    private final ObjectProvider<AiTicketConversionService> conversionServices;
    private final PlatformTransactionManager transactionManager;

    @Override
    public TableDataInfo<AiTicketDraftResponse> page(AiTicketDraftQuery query, PageQuery pageQuery) {
        Page<AiTicketDraft> page = draftMapper.selectPage(pageQuery.build(), new LambdaQueryWrapper<AiTicketDraft>()
            .eq(query.getStatus() != null && !query.getStatus().isBlank(), AiTicketDraft::getStatus, query.getStatus())
            .like(query.getCallerNumber() != null && !query.getCallerNumber().isBlank(), AiTicketDraft::getCallerNumber, query.getCallerNumber())
            .eq(query.getAiAgentId() != null, AiTicketDraft::getAiAgentId, query.getAiAgentId())
            .orderByDesc(AiTicketDraft::getCreateTime));
        return new TableDataInfo<>(page.getRecords().stream().map(value -> toResponse(value, false)).toList(), page.getTotal());
    }

    @Override
    public AiTicketDraftResponse get(Long id) { return toResponse(require(id), true); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AiTicketDraftUpdateRequest request) {
        AiTicketDraft before = requireReviewable(id);
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getId, id).eq(AiTicketDraft::getVersion, request.getVersion())
            .in(AiTicketDraft::getStatus, REVIEWABLE)
            .set(AiTicketDraft::getTitle, request.getTitle())
            .set(AiTicketDraft::getSummary, request.getSummary())
            .set(AiTicketDraft::getFormDataJson, JsonUtils.toJsonString(request.getFormData()))
            .setSql("version = version + 1"));
        if (updated != 1) throw conflict();
        audit(id, "EDIT", before, draftMapper.selectById(id), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long approve(Long id, AiTicketDraftReviewRequest request) {
        AiTicketDraft draft = requireReviewable(id);
        if (!Objects.equals(draft.getVersion(), request.getVersion())) throw conflict();
        AiTicketConversionService conversion = conversionServices.getIfAvailable();
        if (conversion == null) throw new ServiceException("AI 工单转换服务未加载");
        Long ticketId = conversion.convert(new AiTicketConversionService.Command(draft.getId(), draft.getCustomerId(),
            draft.getCallerNumber(), draft.getSourceCallId(), draft.getTicketTemplateId(), draft.getAiAgentId(),
            map(draft.getFormDataJson()), "REVIEW", null, null, "CREATE_ONLY"));
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getId, id).eq(AiTicketDraft::getVersion, request.getVersion())
            .in(AiTicketDraft::getStatus, REVIEWABLE)
            .set(AiTicketDraft::getStatus, "CREATED").set(AiTicketDraft::getFormalTicketId, ticketId)
            .set(AiTicketDraft::getReviewedBy, LoginHelper.getUserId()).set(AiTicketDraft::getReviewedAt, new Date())
            .setSql("version = version + 1"));
        if (updated != 1) throw conflict();
        audit(id, "APPROVE", draft, draftMapper.selectById(id), request.getReason());
        return ticketId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, AiTicketDraftReviewRequest request) {
        AiTicketDraft before = requireReviewable(id);
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getId, id).eq(AiTicketDraft::getVersion, request.getVersion())
            .in(AiTicketDraft::getStatus, REVIEWABLE).set(AiTicketDraft::getStatus, "REJECTED")
            .set(AiTicketDraft::getFailureReason, request.getReason()).set(AiTicketDraft::getReviewedBy, LoginHelper.getUserId())
            .set(AiTicketDraft::getReviewedAt, new Date()).setSql("version = version + 1"));
        if (updated != 1) throw conflict();
        audit(id, "REJECT", before, draftMapper.selectById(id), request.getReason());
    }

    @Override
    public AiTicketDraftBatchReviewResponse batchApprove(AiTicketDraftBatchReviewRequest request) {
        return batch(request, true);
    }

    @Override
    public AiTicketDraftBatchReviewResponse batchReject(AiTicketDraftBatchReviewRequest request) {
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new ServiceException("请填写批量驳回原因");
        }
        return batch(request, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void regenerate(Long id, AiTicketDraftReviewRequest request) {
        AiTicketDraft before = require(id);
        if (!REVIEWABLE.contains(before.getStatus()) && !"FAILED".equals(before.getStatus())) {
            throw new ServiceException("当前草稿状态不能重新生成");
        }
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getId, id).eq(AiTicketDraft::getVersion, request.getVersion())
            .in(AiTicketDraft::getStatus, List.of("PENDING_REVIEW", "LOW_CONFIDENCE", "FAILED"))
            .set(AiTicketDraft::getStatus, "GENERATING")
            .set(AiTicketDraft::getFailureReason, null).setSql("version = version + 1"));
        if (updated != 1) throw conflict();
        AiTicketDraftTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTicketDraftTask>()
            .eq(AiTicketDraftTask::getPolicyId, before.getPolicyId())
            .eq(AiTicketDraftTask::getBusinessCallId, before.getSourceCallId()).last("LIMIT 1"));
        if (task == null) throw new ServiceException("原始草稿任务不存在");
        task.setStatus("READY"); task.setRetryCount(0); task.setNextRetryAt(null); task.setFailureReason(null);
        taskMapper.updateById(task);
        audit(id, "REGENERATE", before, draftMapper.selectById(id), request.getReason());
        dispatcher.dispatchAfterCommit(task.getId(), task.getTenantId());
    }

    private AiTicketDraftBatchReviewResponse batch(AiTicketDraftBatchReviewRequest request, boolean approveAction) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Map<Long, AiTicketDraftBatchReviewRequest.Item> uniqueItems = new LinkedHashMap<>();
        for (AiTicketDraftBatchReviewRequest.Item item : request.getItems()) {
            uniqueItems.putIfAbsent(item.getId(), item);
        }
        List<AiTicketDraftBatchReviewResponse.Item> results = new ArrayList<>();
        for (AiTicketDraftBatchReviewRequest.Item item : uniqueItems.values()) {
            try {
                Long ticketId = transaction.execute(status -> {
                    AiTicketDraftReviewRequest review = new AiTicketDraftReviewRequest();
                    review.setVersion(item.getVersion());
                    review.setReason(request.getReason());
                    if (approveAction) return approve(item.getId(), review);
                    reject(item.getId(), review);
                    return null;
                });
                results.add(new AiTicketDraftBatchReviewResponse.Item(item.getId(), true, ticketId,
                    approveAction ? "已创建正式工单" : "已驳回"));
            } catch (Exception exception) {
                String message = errorMessage(exception);
                try {
                    transaction.executeWithoutResult(status -> audit(item.getId(),
                        approveAction ? "BATCH_APPROVE_FAILED" : "BATCH_REJECT_FAILED",
                        draftMapper.selectById(item.getId()), null, message));
                } catch (Exception ignored) {
                    // The original item failure remains the authoritative result.
                }
                results.add(new AiTicketDraftBatchReviewResponse.Item(item.getId(), false, null, message));
            }
        }
        int success = (int) results.stream().filter(AiTicketDraftBatchReviewResponse.Item::success).count();
        return new AiTicketDraftBatchReviewResponse(results.size(), success, results.size() - success, results);
    }

    private String errorMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getMessage() == null) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank() ? "处理失败" : current.getMessage();
    }

    private AiTicketDraft require(Long id) {
        AiTicketDraft value = draftMapper.selectById(id);
        if (value == null) throw new ServiceException("AI 工单草稿不存在");
        return value;
    }
    private AiTicketDraft requireReviewable(Long id) {
        AiTicketDraft value = require(id);
        if (!REVIEWABLE.contains(value.getStatus())) throw new ServiceException("当前草稿状态不能审核或修改");
        return value;
    }
    private ServiceException conflict() { return new ServiceException("草稿已被其他用户修改，请刷新后重试"); }
    private void audit(Long id, String action, Object before, Object after, String remark) {
        AiTicketDraftAudit value = new AiTicketDraftAudit(); value.setDraftId(id); value.setActionType(action);
        value.setBeforeDataJson(JsonUtils.toJsonString(before)); value.setAfterDataJson(JsonUtils.toJsonString(after)); value.setRemark(remark);
        auditMapper.insert(value);
    }
    private AiTicketDraftResponse toResponse(AiTicketDraft value, boolean detail) {
        AiTicketDraftResponse response = new AiTicketDraftResponse();
        response.setId(value.getId()); response.setAiAgentId(value.getAiAgentId()); response.setSourceCallId(value.getSourceCallId());
        response.setCustomerId(value.getCustomerId()); response.setCallerNumber(value.getCallerNumber()); response.setTicketTemplateId(value.getTicketTemplateId());
        response.setStatus(value.getStatus()); response.setConfidence(value.getConfidence()); response.setTitle(value.getTitle()); response.setSummary(value.getSummary());
        response.setFormData(map(value.getFormDataJson())); response.setMissingFields(list(value.getMissingFieldsJson(), String.class));
        response.setEvidence(list(value.getEvidenceJson(), Map.class)); response.setFailureReason(value.getFailureReason());
        response.setFormalTicketId(value.getFormalTicketId()); response.setVersion(value.getVersion());
        if (value.getCreateTime() != null) response.setCreateTime(value.getCreateTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        if (detail) {
            response.setConversation(conversation(value.getSourceCallId()));
            AiCallRecordingSource source = callSourceMapper.selectOne(new LambdaQueryWrapper<AiCallRecordingSource>()
                .eq(AiCallRecordingSource::getBusinessCallId, value.getSourceCallId()).last("LIMIT 1"));
            if (source != null) {
                response.setRecordingOssId(source.getRecordingOssId());
                response.setRecordingFileName(source.getRecordingFileName());
            }
        }
        return response;
    }
    private String conversation(String callId) {
        AiCallTranscript value = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
            .eq(AiCallTranscript::getBusinessCallId, callId).eq(AiCallTranscript::getStatus, "SUCCESS")
            .orderByDesc(AiCallTranscript::getFinishedAt).last("LIMIT 1"));
        return value == null ? null : value.getFullText();
    }
    @SuppressWarnings("unchecked") private Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return JsonUtils.getObjectMapper().readValue(json, LinkedHashMap.class); } catch (Exception e) { return new LinkedHashMap<>(); }
    }
    @SuppressWarnings("unchecked") private <T> List<T> list(String json, Class<?> type) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try { return JsonUtils.getObjectMapper().readValue(json, ArrayList.class); } catch (Exception e) { return new ArrayList<>(); }
    }
}
