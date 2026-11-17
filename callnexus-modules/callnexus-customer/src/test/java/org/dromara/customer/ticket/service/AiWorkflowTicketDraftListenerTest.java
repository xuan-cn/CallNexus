package org.dromara.customer.ticket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.AiTicketDraft;
import org.dromara.ai.mapper.AiTicketDraftAuditMapper;
import org.dromara.ai.mapper.AiTicketDraftMapper;
import org.dromara.ai.service.model.AiWorkflowTicketDraftEvent;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormFieldType;
import org.dromara.customer.form.domain.response.FormTemplateResponse;
import org.dromara.customer.form.service.FormTemplateApplicationService;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.customer.ticket.service.impl.AiWorkflowTicketDraftListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AiWorkflowTicketDraftListenerTest {
    private final CallSessionMapper calls = mock(CallSessionMapper.class);
    private final TicketMapper tickets = mock(TicketMapper.class);
    private final AiTicketDraftMapper drafts = mock(AiTicketDraftMapper.class);
    private final AiTicketDraftAuditMapper audits = mock(AiTicketDraftAuditMapper.class);
    private final FormTemplateApplicationService templates = mock(FormTemplateApplicationService.class);
    private final AiWorkflowTicketDraftListener listener = new AiWorkflowTicketDraftListener(calls, tickets, drafts, audits, templates, new ObjectMapper());

    @Test
    void retainsOnlyAllowedMappedFieldsAndFlagsOtherRequiredFields() throws Exception {
        when(calls.selectOne(any())).thenReturn(new CallSession());
        FormTemplateResponse template = new FormTemplateResponse();
        template.setBusinessType(FormBusinessType.TICKET);
        template.setEnabled(true);
        template.setTemplateName("表具报修");
        template.setFields(List.of(field("address", true), field("phone", false)));
        when(templates.get(7L)).thenReturn(template);
        listener.retain(new AiWorkflowTicketDraftEvent("call", 9L, 7L,
            Map.of("address", "A", "phone", "B", "unknown", "C")));
        var captured = ArgumentCaptor.forClass(AiTicketDraft.class);
        verify(drafts).insert(captured.capture());
        assertThat(captured.getValue().getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(captured.getValue().getFormDataJson()).isEqualTo("{\"address\":\"A\"}");
        assertThat(captured.getValue().getMissingFieldsJson()).isEqualTo("[\"phone\"]");
        assertThat(captured.getValue().getTicketTemplateId()).isEqualTo(7L);
        verify(audits).insert(any(org.dromara.ai.domain.AiTicketDraftAudit.class));
    }

    @Test
    void neverCreatesAnotherDraftForExistingTicketOrDraft() throws Exception {
        when(calls.selectOne(any())).thenReturn(new CallSession());
        when(tickets.selectCount(any())).thenReturn(1L, 0L);
        when(drafts.selectCount(any())).thenReturn(1L);
        var event = new AiWorkflowTicketDraftEvent("call", 9L, 7L, Map.of("address", "A"));
        listener.retain(event);
        listener.retain(event);
        verify(drafts, never()).insert(any(AiTicketDraft.class));
    }

    private FormTemplateResponse.FieldResponse field(String code, boolean aiFill) {
        var field = new FormTemplateResponse.FieldResponse();
        field.setFieldCode(code);
        field.setEnabled(true);
        field.setRequired(true);
        field.setAiFillEnabled(aiFill);
        field.setFieldType(FormFieldType.INPUT);
        return field;
    }
}
