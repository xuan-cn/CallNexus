package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiTicketDraft;
import org.dromara.ai.domain.AiTicketDraftAudit;
import org.dromara.ai.domain.AiTicketPolicy;
import org.dromara.ai.mapper.AiTicketDraftAuditMapper;
import org.dromara.ai.mapper.AiTicketDraftMapper;
import org.dromara.ai.service.AiTicketConversionService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiTicketAutoCreationService {
    private static final Set<String> ANONYMOUS_NUMBERS = Set.of("anonymous", "unknown", "private", "restricted");

    private final AiTicketDraftMapper draftMapper;
    private final AiTicketDraftAuditMapper auditMapper;
    private final ObjectProvider<AiTicketConversionService> conversionServices;

    @Transactional(rollbackFor = Exception.class)
    public void attempt(AiTicketPolicy policy, AiTicketDraft draft) {
        if (!"AUTO_CREATE".equals(policy.getCreationMode())) return;
        AiTicketConversionService conversion = conversionServices.getIfAvailable();
        if (conversion == null) throw new ServiceException("AI 工单转换服务未加载");

        List<String> reasons = new ArrayList<>();
        BigDecimal threshold = policy.getConfidenceThreshold() == null
            ? new BigDecimal("0.8") : policy.getConfidenceThreshold();
        if (draft.getConfidence() == null || draft.getConfidence().compareTo(threshold) < 0) {
            reasons.add("置信度未达到 " + threshold.stripTrailingZeros().toPlainString());
        }
        List<String> missingFields = list(draft.getMissingFieldsJson());
        if (!missingFields.isEmpty()) reasons.add("缺少必填字段：" + String.join("、", missingFields));
        if (draft.getCustomerId() == null && !validCallerNumber(draft.getCallerNumber())) {
            reasons.add("客户号码无效或无法唯一识别");
        }

        Long duplicateTicketId = null;
        if (reasons.isEmpty() && !"ALLOW".equals(policy.getDuplicatePolicy())) {
            duplicateTicketId = conversion.findDuplicateTicket(draft.getCustomerId(), draft.getCallerNumber(),
                draft.getTicketTemplateId(), policy.getDuplicateWindowHours());
            if (duplicateTicketId != null) {
                reasons.add(duplicateReason(policy.getDuplicatePolicy(), duplicateTicketId));
            }
        }
        if (!reasons.isEmpty()) {
            downgrade(draft, reasons);
            return;
        }

        Long ticketId = conversion.convert(new AiTicketConversionService.Command(draft.getId(), draft.getCustomerId(),
            draft.getCallerNumber(), draft.getSourceCallId(), draft.getTicketTemplateId(), draft.getAiAgentId(),
            map(draft.getFormDataJson()), "AUTO", policy.getCustomerTemplateId(), policy.getDefaultSkillGroupId(),
            normalizeAfterCreateAction(policy.getAfterCreateAction())));
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getId, draft.getId())
            .in(AiTicketDraft::getStatus, List.of("PENDING_REVIEW", "LOW_CONFIDENCE"))
            .set(AiTicketDraft::getStatus, "CREATED")
            .set(AiTicketDraft::getFormalTicketId, ticketId)
            .set(AiTicketDraft::getReviewedAt, new Date())
            .set(AiTicketDraft::getFailureReason, null)
            .setSql("version = version + 1"));
        if (updated != 1) throw new ServiceException("AI 工单草稿状态已变化，自动建单已取消");
        audit(draft.getId(), "AUTO_CREATE", draft, draftMapper.selectById(draft.getId()),
            "满足自动建单安全闸门，正式工单ID=" + ticketId);
        log.info("AI 工单自动创建成功，draftId={}，ticketId={}，callId={}，afterCreateAction={}",
            draft.getId(), ticketId, draft.getSourceCallId(), normalizeAfterCreateAction(policy.getAfterCreateAction()));
    }

    private void downgrade(AiTicketDraft draft, List<String> reasons) {
        String reason = "自动建单已降级人工审核：" + String.join("；", reasons);
        String status = "LOW_CONFIDENCE".equals(draft.getStatus()) ? "LOW_CONFIDENCE" : "PENDING_REVIEW";
        draftMapper.update(null, new LambdaUpdateWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getId, draft.getId())
            .in(AiTicketDraft::getStatus, List.of("PENDING_REVIEW", "LOW_CONFIDENCE"))
            .set(AiTicketDraft::getStatus, status)
            .set(AiTicketDraft::getFailureReason, reason)
            .setSql("version = version + 1"));
        audit(draft.getId(), "AUTO_DOWNGRADE", draft, draftMapper.selectById(draft.getId()), reason);
        log.info("AI 工单自动创建降级人工审核，draftId={}，callId={}，reasons={}",
            draft.getId(), draft.getSourceCallId(), reasons);
    }

    private boolean validCallerNumber(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase();
        return !ANONYMOUS_NUMBERS.contains(normalized) && normalized.matches("^\\+?[0-9]{5,20}$");
    }

    private String normalizeAfterCreateAction(String value) {
        if ("SUBMIT".equals(value) || "RESOLVE".equals(value)) return value;
        return "CREATE_ONLY";
    }

    private String duplicateReason(String policy, Long ticketId) {
        if ("MERGE_PENDING".equals(policy)) {
            return "重复窗口内已存在工单 " + ticketId + "，需人工确认是否合并";
        }
        return "重复窗口内已存在工单 " + ticketId + "，已阻止自动建单";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonUtils.getObjectMapper().readValue(json, Map.class);
        } catch (Exception exception) {
            throw new ServiceException("AI 工单字段数据无效：" + exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> list(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JsonUtils.getObjectMapper().readValue(json, List.class);
        } catch (Exception exception) {
            return List.of("字段校验结果无法解析");
        }
    }

    private void audit(Long draftId, String action, Object before, Object after, String remark) {
        AiTicketDraftAudit value = new AiTicketDraftAudit();
        value.setDraftId(draftId);
        value.setActionType(action);
        value.setBeforeDataJson(JsonUtils.toJsonString(before));
        value.setAfterDataJson(JsonUtils.toJsonString(after));
        value.setRemark(limit(remark, 500));
        auditMapper.insert(value);
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
