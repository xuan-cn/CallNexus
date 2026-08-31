package org.dromara.ai.service;

import org.dromara.ai.domain.AiTicketDraft;
import org.dromara.ai.domain.AiTicketPolicy;
import org.dromara.ai.mapper.AiTicketDraftAuditMapper;
import org.dromara.ai.mapper.AiTicketDraftMapper;
import org.dromara.ai.service.impl.AiTicketAutoCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiTicketAutoCreationServiceTest {

    private AiTicketDraftMapper draftMapper;
    private AiTicketConversionService conversion;
    private AiTicketAutoCreationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        draftMapper = mock(AiTicketDraftMapper.class);
        conversion = mock(AiTicketConversionService.class);
        ObjectProvider<AiTicketConversionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(conversion);
        when(draftMapper.update(any(), any())).thenReturn(1);
        service = new AiTicketAutoCreationService(draftMapper, mock(AiTicketDraftAuditMapper.class), provider);
    }

    @Test
    void shouldCreateTicketWhenAllSafetyGatesPass() {
        AiTicketDraft draft = draft(new BigDecimal("0.92"), "[]");
        AiTicketPolicy policy = policy("ALLOW");
        policy.setDefaultSkillGroupId(8L);
        policy.setAfterCreateAction("RESOLVE");
        when(conversion.convert(any())).thenReturn(99L);
        when(draftMapper.selectById(10L)).thenReturn(draft);

        service.attempt(policy, draft);

        ArgumentCaptor<AiTicketConversionService.Command> captor = ArgumentCaptor.forClass(AiTicketConversionService.Command.class);
        verify(conversion).convert(captor.capture());
        assertThat(captor.getValue().conversionMode()).isEqualTo("AUTO");
        assertThat(captor.getValue().defaultSkillGroupId()).isEqualTo(8L);
        assertThat(captor.getValue().afterCreateAction()).isEqualTo("RESOLVE");
        assertThat(captor.getValue().customerTemplateId()).isEqualTo(21L);
    }

    @Test
    void shouldDowngradeWhenConfidenceOrRequiredFieldsFail() {
        AiTicketDraft draft = draft(new BigDecimal("0.65"), "[\"issueType\"]");
        when(draftMapper.selectById(10L)).thenReturn(draft);

        service.attempt(policy("ALLOW"), draft);

        verify(conversion, never()).convert(any());
        verify(conversion, never()).findDuplicateTicket(any(), any(), any(), any());
        verify(draftMapper).update(isNull(), any());
    }

    @Test
    void shouldDowngradeWhenDuplicateExistsInsideWindow() {
        AiTicketDraft draft = draft(new BigDecimal("0.95"), "[]");
        when(conversion.findDuplicateTicket(null, "19029157428", 20L, 24)).thenReturn(88L);
        when(draftMapper.selectById(10L)).thenReturn(draft);

        service.attempt(policy("SKIP"), draft);

        verify(conversion, never()).convert(any());
        verify(conversion).findDuplicateTicket(null, "19029157428", 20L, 24);
    }

    private AiTicketPolicy policy(String duplicatePolicy) {
        AiTicketPolicy policy = new AiTicketPolicy();
        policy.setCreationMode("AUTO_CREATE");
        policy.setConfidenceThreshold(new BigDecimal("0.8"));
        policy.setCustomerTemplateId(21L);
        policy.setDuplicatePolicy(duplicatePolicy);
        policy.setDuplicateWindowHours(24);
        policy.setAfterCreateAction("CREATE_ONLY");
        return policy;
    }

    private AiTicketDraft draft(BigDecimal confidence, String missingFields) {
        AiTicketDraft draft = new AiTicketDraft();
        draft.setId(10L);
        draft.setAiAgentId(2L);
        draft.setSourceCallId("call-1");
        draft.setCallerNumber("19029157428");
        draft.setTicketTemplateId(20L);
        draft.setStatus(confidence.compareTo(new BigDecimal("0.8")) < 0 ? "LOW_CONFIDENCE" : "PENDING_REVIEW");
        draft.setConfidence(confidence);
        draft.setMissingFieldsJson(missingFields);
        draft.setFormDataJson("{}");
        return draft;
    }
}
