package org.dromara.customer.ticket.service;

import org.dromara.ai.service.AiTicketBusinessContextProvider;
import org.dromara.ai.service.model.AiTicketTemplateContext;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.response.FormTemplateResponse;
import org.dromara.customer.form.service.DynamicFormSubmissionService;
import org.dromara.customer.form.service.FormTemplateApplicationService;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.customer.ticket.service.impl.AiWorkflowTicketServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiWorkflowTicketServiceTest {
    private final CallSessionMapper calls = mock(CallSessionMapper.class);
    private final TicketMapper tickets = mock(TicketMapper.class);
    private final AiTicketBusinessContextProvider templates = mock(AiTicketBusinessContextProvider.class);
    private final DynamicFormSubmissionService forms = mock(DynamicFormSubmissionService.class);
    private final TicketApplicationService application = mock(TicketApplicationService.class);
    private final FormTemplateApplicationService formTemplates = mock(FormTemplateApplicationService.class);
    private final AiWorkflowTicketServiceImpl service = new AiWorkflowTicketServiceImpl(calls, tickets, templates, forms, application, formTemplates);

    @Test
    void testModeValidatesButNeverWrites() {
        template(7L, "address");
        previewTemplate("address");
        Map<String, Object> values = Map.of("address", "A");
        var result = service.register(7L, values, null, 1L, true);
        assertThat(result.validationError()).isNull();
        assertThat(result.ticketId()).isNull();
        assertThat(result.preview().templateName()).isEqualTo("报修工单");
        assertThat(result.preview().fields()).singleElement().satisfies(field -> {
            assertThat(field.mapped()).isTrue();
            assertThat(field.value()).isEqualTo("A");
        });
        verify(forms).validate(7L, FormBusinessType.TICKET, values);
        verifyNoInteractions(application, calls, tickets);
    }

    @Test
    void validationFailureStillReturnsUnmappedAndEmptyFields() {
        template(7L, "address");
        previewTemplate("address", "phone");
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("address", null);
        doThrow(new ServiceException("缺少地址")).when(forms).validate(7L, FormBusinessType.TICKET, values);
        var result = service.register(7L, values, null, 1L, true);
        assertThat(result.validationError()).isEqualTo("缺少地址");
        assertThat(result.preview().fields()).satisfiesExactly(address -> {
            assertThat(address.required()).isTrue();
            assertThat(address.mapped()).isTrue();
            assertThat(address.value()).isNull();
        }, phone -> {
            assertThat(phone.mapped()).isFalse();
            assertThat(phone.value()).isNull();
        });
        verifyNoInteractions(application, calls, tickets);
    }

    @Test
    void missingRequiredFieldDoesNotCreateTicket() {
        when(calls.selectOne(any())).thenReturn(new CallSession());
        template(7L, "address");
        doThrow(new ServiceException("缺少地址")).when(forms).validate(7L, FormBusinessType.TICKET, Map.of());
        assertThat(service.register(7L, Map.of(), "call-1", 1L, false).validationError()).isEqualTo("缺少地址");
        verifyNoInteractions(application);
    }

    @Test
    void sameAssistantCanRegisterDifferentTemplatesOnDifferentCalls() {
        CallSession call = new CallSession();
        call.setCallerNumber("12345");
        when(calls.selectOne(any())).thenReturn(call);
        when(application.create(any())).thenReturn(31L, 32L);
        template(7L, "address");
        template(8L, "meter");
        assertThat(service.register(7L, Map.of("address", "A"), "call-1", 1L, false).ticketId()).isEqualTo(31L);
        assertThat(service.register(8L, Map.of("meter", "B"), "call-2", 1L, false).ticketId()).isEqualTo(32L);
        verify(application).create(argThat(r -> r != null && r.getTemplateId().equals(7L) && r.getFormData().equals(Map.of("address", "A"))));
        verify(application).create(argThat(r -> r != null && r.getTemplateId().equals(8L) && r.getFormData().equals(Map.of("meter", "B"))));
    }

    @Test
    void reusesExistingTicketWithoutChangingItsTemplate() {
        when(calls.selectOne(any())).thenReturn(new CallSession());
        Ticket ticket = new Ticket();
        ticket.setId(31L);
        ticket.setTemplateId(7L);
        when(tickets.selectOne(any())).thenReturn(ticket);
        assertThat(service.register(7L, Map.of(), "call-1", 1L, false).ticketId()).isEqualTo(31L);
        assertThat(service.register(8L, Map.of(), "call-1", 1L, false).validationError()).contains("其他类型");
        verifyNoInteractions(application);
    }

    private void template(Long id, String field) {
        when(templates.load(id, null)).thenReturn(new AiTicketTemplateContext(null, "", "",
            List.of(new AiTicketTemplateContext.Field(field, field, "INPUT", true, null, List.of()))));
    }

    private void previewTemplate(String... codes) {
        var template = new FormTemplateResponse();
        template.setBusinessType(FormBusinessType.TICKET);
        template.setTemplateName("报修工单");
        for (String code : codes) {
            var field = new FormTemplateResponse.FieldResponse();
            field.setFieldCode(code);
            field.setFieldName(code);
            field.setRequired(true);
            template.getFields().add(field);
        }
        when(formTemplates.get(7L)).thenReturn(template);
    }
}
