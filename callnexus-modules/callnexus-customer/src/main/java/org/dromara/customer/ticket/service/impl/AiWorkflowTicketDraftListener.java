package org.dromara.customer.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiTicketDraft;
import org.dromara.ai.domain.AiTicketDraftAudit;
import org.dromara.ai.mapper.AiTicketDraftAuditMapper;
import org.dromara.ai.mapper.AiTicketDraftMapper;
import org.dromara.ai.service.model.AiWorkflowTicketDraftEvent;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormFieldType;
import org.dromara.customer.form.domain.response.FormTemplateResponse;
import org.dromara.customer.form.service.FormTemplateApplicationService;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;

@Component
@RequiredArgsConstructor
public class AiWorkflowTicketDraftListener {
    private final CallSessionMapper calls;
    private final TicketMapper tickets;
    private final AiTicketDraftMapper drafts;
    private final AiTicketDraftAuditMapper audits;
    private final FormTemplateApplicationService templates;
    private final ObjectMapper json;

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void retain(AiWorkflowTicketDraftEvent event) throws JsonProcessingException {
        CallSession call = calls.selectOne(new LambdaQueryWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, event.businessCallId()).last("LIMIT 1 FOR UPDATE"));
        if (call == null || tickets.selectCount(new LambdaQueryWrapper<Ticket>()
            .eq(Ticket::getSourceCallId, event.businessCallId())) > 0) return;
        if (drafts.selectCount(new LambdaQueryWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getSourceCallId, event.businessCallId())) > 0) return;
        FormTemplateResponse template;
        try {
            template = templates.get(event.templateId());
        } catch (ServiceException exception) {
            return;
        }
        if (template == null || template.getBusinessType() != FormBusinessType.TICKET
            || !Boolean.TRUE.equals(template.getEnabled())) return;

        var values = new LinkedHashMap<String, Object>();
        var missing = new ArrayList<String>();
        for (var field : template.getFields()) {
            if (!Boolean.TRUE.equals(field.getEnabled())) continue;
            String code = field.getFieldCode();
            Object value = event.formData().get(code);
            if (Boolean.TRUE.equals(field.getAiFillEnabled()) && field.getFieldType() != FormFieldType.FILE && value != null) {
                values.put(code, value);
            }
            if (Boolean.TRUE.equals(field.getRequired()) && !values.containsKey(code)) missing.add(code);
        }
        if (values.isEmpty()) return;
        AiTicketDraft draft = new AiTicketDraft();
        draft.setPolicyId(AiWorkflowTicketDraftEvent.WORKFLOW_POLICY_ID);
        draft.setAiAgentId(event.agentId());
        draft.setSourceCallId(event.businessCallId());
        draft.setTicketTemplateId(event.templateId());
        draft.setCustomerId(call.getCustomerId());
        draft.setCallerNumber(call.getDirection() != null && call.getDirection().startsWith("OUTBOUND")
            ? call.getCalledNumber() : call.getCallerNumber());
        draft.setStatus("PENDING_REVIEW");
        draft.setTitle(template.getTemplateName());
        draft.setSummary("语音流程已结束，未生成正式工单。请核对已采集内容并补齐后提交审核。");
        draft.setFormDataJson(json.writeValueAsString(values));
        draft.setMissingFieldsJson(json.writeValueAsString(missing));
        draft.setEvidenceJson("[]");
        draft.setVersion(0);
        drafts.insert(draft);
        AiTicketDraftAudit audit = new AiTicketDraftAudit();
        audit.setDraftId(draft.getId());
        audit.setActionType("WORKFLOW_RETAIN");
        audit.setAfterDataJson(json.writeValueAsString(draft));
        audit.setRemark("保留流程采集字段，等待人工确认");
        audits.insert(audit);
    }
}
