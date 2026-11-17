package org.dromara.customer.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.response.AiWorkflowTicketPreview;
import org.dromara.ai.service.AiTicketBusinessContextProvider;
import org.dromara.ai.service.AiWorkflowTicketService;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.service.DynamicFormSubmissionService;
import org.dromara.customer.form.service.FormTemplateApplicationService;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.request.CreateTicketRequest;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.customer.ticket.service.TicketApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AiWorkflowTicketServiceImpl implements AiWorkflowTicketService {
    private final CallSessionMapper callSessionMapper;
    private final TicketMapper ticketMapper;
    private final AiTicketBusinessContextProvider templateProvider;
    private final DynamicFormSubmissionService formSubmissionService;
    private final TicketApplicationService ticketService;
    private final FormTemplateApplicationService formTemplates;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result register(Long templateId, Map<String, Object> formData, String businessCallId, Long agentId, boolean test) {
        CallSession call = null;
        if (!test) {
            if (businessCallId == null || businessCallId.isBlank()) return new Result(null, "缺少关联通话，未登记工单");
            // Serialize registration for the same call, including workflow retries.
            call = callSessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
                .eq(CallSession::getBusinessCallId, businessCallId).last("LIMIT 1 FOR UPDATE"));
            if (call == null) return new Result(null, "关联通话不存在，未登记工单");
            Ticket existing = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getSourceCallId, businessCallId).orderByAsc(Ticket::getId).last("LIMIT 1 FOR UPDATE"));
            if (existing != null) {
                return Objects.equals(existing.getTemplateId(), templateId)
                    ? new Result(existing.getId(), null) : new Result(null, "本通话已登记其他类型的工单");
            }
        }
        AiWorkflowTicketPreview preview = null;
        try {
            if (test) preview = preview(templateId, formData);
            var fields = templateProvider.load(templateId, null).fieldMap();
            for (String code : formData.keySet()) {
                var field = fields.get(code);
                if (field == null || "FILE".equals(field.type())) return new Result(null, "工单字段不允许自动填写：" + code, preview);
            }
            formSubmissionService.validate(templateId, FormBusinessType.TICKET, formData);
        } catch (ServiceException exception) {
            return new Result(null, exception.getMessage(), preview);
        }
        if (test) return new Result(null, null, preview);
        CreateTicketRequest request = new CreateTicketRequest();
        request.setTemplateId(templateId);
        request.setSourceType("AI_WORKFLOW");
        request.setSourceCallId(businessCallId);
        request.setAiAgentId(agentId);
        request.setCustomerId(call.getCustomerId());
        request.setCallerNumber(call.getDirection() != null && call.getDirection().startsWith("OUTBOUND")
            ? call.getCalledNumber() : call.getCallerNumber());
        request.setFormData(formData);
        return new Result(ticketService.create(request), null);
    }

    private AiWorkflowTicketPreview preview(Long templateId, Map<String, Object> formData) {
        var template = formTemplates.get(templateId);
        if (template == null || template.getBusinessType() != FormBusinessType.TICKET) {
            throw new ServiceException("工单模板不存在");
        }
        var rows = new ArrayList<AiWorkflowTicketPreview.Field>();
        var knownCodes = new HashSet<String>();
        for (var field : template.getFields()) {
            if (Boolean.FALSE.equals(field.getEnabled())) continue;
            String code = field.getFieldCode();
            knownCodes.add(code);
            rows.add(new AiWorkflowTicketPreview.Field(code, field.getFieldName(), Boolean.TRUE.equals(field.getRequired()),
                formData.containsKey(code), formData.get(code)));
        }
        formData.forEach((code, value) -> {
            if (!knownCodes.contains(code)) rows.add(new AiWorkflowTicketPreview.Field(code, code, false, true, value));
        });
        return new AiWorkflowTicketPreview(templateId.toString(), template.getTemplateName(), rows);
    }
}
