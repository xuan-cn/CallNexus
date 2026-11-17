package org.dromara.outbound.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.form.service.FormTemplateApplicationService;
import org.dromara.customer.form.service.DynamicFormSubmissionService;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.response.FormTemplateResponse;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketStatus;
import org.dromara.customer.ticket.domain.response.TicketResponse;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.customer.ticket.service.TicketApplicationService;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.response.OutboundBlacklistMatch;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.mapper.OutboundTaskSourceMapper;
import org.dromara.outbound.mapper.TicketCallbackPolicyMapper;
import org.dromara.outbound.service.impl.TicketCallbackService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class TicketCallbackServiceTest {
    private final TicketApplicationService tickets = mock(TicketApplicationService.class);
    private final TicketMapper ticketMapper = mock(TicketMapper.class);
    private final OutboundTaskMapper tasks = mock(OutboundTaskMapper.class);
    private final OutboundMemberMapper members = mock(OutboundMemberMapper.class);
    private final OutboundBlacklistChecker blacklist = mock(OutboundBlacklistChecker.class);
    private final TicketCallbackPolicyMapper policies = mock(TicketCallbackPolicyMapper.class);
    private final FormTemplateApplicationService templates = mock(FormTemplateApplicationService.class);
    private final DynamicFormSubmissionService formData = mock(DynamicFormSubmissionService.class);
    private final CallSessionMapper calls = mock(CallSessionMapper.class);
    private final TicketCallbackService service = new TicketCallbackService(tickets, ticketMapper,
        mock(CustomerApplicationService.class), templates, tasks,
        mock(OutboundTaskSourceMapper.class), members, new PhoneNumberNormalizer(), blacklist, policies, formData, calls);

    @BeforeAll
    static void registerCallSessionMapping() {
        var builder = new MapperBuilderAssistant(new MybatisConfiguration(), "call-session");
        builder.setCurrentNamespace(CallSessionMapper.class.getName());
        TableInfoHelper.initTableInfo(builder, CallSession.class);
    }

    @Test
    void twoTicketsWithTheSamePhoneGetIndependentMembers() {
        prepare();
        var request = new TicketCallbackService.Request(7L, "13800138000", true);
        service.enqueue(1L, request);
        service.enqueue(2L, request);
        var captured = ArgumentCaptor.forClass(OutboundMember.class);
        verify(members, times(2)).insert(captured.capture());
        assertThat(captured.getAllValues()).extracting(OutboundMember::getSourceId).containsExactly(1L, 2L);
        assertThat(captured.getAllValues()).allSatisfy(member -> {
            assertThat(member.getSourceType()).isEqualTo(TicketCallbackService.SOURCE);
            assertThat(member.getPhoneNumber()).isEqualTo("13800138000");
            assertThat(member.getStatus()).isEqualTo("PENDING");
        });
    }

    @Test
    void rejectsDuplicateAndBlacklistedRequestsWithoutCreatingMembers() {
        prepare();
        when(members.selectOne(any())).thenReturn(new OutboundMember(), null);
        when(blacklist.check(7L, "13800138000")).thenReturn(new OutboundBlacklistMatch());
        var request = new TicketCallbackService.Request(7L, "13800138000", true);
        assertThatThrownBy(() -> service.enqueue(1L, request)).isInstanceOf(ServiceException.class).hasMessageContaining("已有");
        assertThatThrownBy(() -> service.enqueue(1L, request)).isInstanceOf(ServiceException.class).hasMessageContaining("黑名单");
        verify(members, never()).insert(any(OutboundMember.class));
    }

    @Test
    void rejectsUnconfirmedAndUnrelatedNumbers() {
        prepare();
        assertThatThrownBy(() -> service.enqueue(1L, new TicketCallbackService.Request(7L, "13800138000", false)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("确认");
        assertThatThrownBy(() -> service.enqueue(1L, new TicketCallbackService.Request(7L, "13900139000", true)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不在当前工单");
        verify(members, never()).insert(any(OutboundMember.class));
    }

    @Test
    void automaticCallbackUsesConfirmedFormPhoneAndKeepsOneMemberPerResolution() {
        LocalDateTime resolved = LocalDateTime.of(2026, 11, 12, 14, 0);
        Ticket ticket = prepareAutomatic(resolved);
        LocalDateTime due = resolved.plusMinutes(30);
        service.enqueueAutomatic(1L, due.minusSeconds(1));
        verify(members, never()).insert(any(OutboundMember.class));
        service.enqueueAutomatic(1L, due);

        var captor = ArgumentCaptor.forClass(OutboundMember.class);
        verify(members).insert(captor.capture());
        OutboundMember member = captor.getValue();
        assertThat(member.getPhoneNumber()).isEqualTo("13800138000");
        assertThat(member.getCallbackResolvedAt()).isEqualTo(resolved);
        assertThat(member.getCallbackPolicyRevision()).isEqualTo("policy-1");
        assertThat(member.getCallbackContactRevision()).isEqualTo("contact-1");

        ticket.setTicketStatus(TicketStatus.CLOSED);
        when(members.selectOne(any())).thenReturn(member);
        service.enqueueAutomatic(1L, due.plusMinutes(1));
        verify(members).insert(any(OutboundMember.class));
    }

    @Test
    void noAutomaticCallbackForOldResolutionOrUnconfirmedPhone() {
        LocalDateTime resolved = LocalDateTime.of(2026, 11, 12, 14, 0);
        prepareAutomatic(resolved);
        when(policies.contact(any(), eq(1L))).thenReturn(null);
        service.enqueueAutomatic(1L, resolved.plusHours(1));
        when(policies.policy(any(), eq(9L))).thenReturn(new TicketCallbackPolicyMapper.Policy(
            "AUTO", 7L, 30, 120, "policy-2", resolved.plusSeconds(1), false));
        service.enqueueAutomatic(1L, resolved.plusHours(1));
        verify(members, never()).insert(any(OutboundMember.class));
    }

    @Test
    void preDialStopsExpiredReopenedOrRevokedAutomaticCallback() {
        LocalDateTime resolved = LocalDateTime.of(2026, 11, 12, 14, 0);
        Ticket ticket = prepareAutomatic(resolved);
        service.enqueueAutomatic(1L, resolved.plusHours(1));
        var captor = ArgumentCaptor.forClass(OutboundMember.class);
        verify(members).insert(captor.capture());
        OutboundMember member = captor.getValue();
        assertThat(service.automaticStopReason(member, resolved.plusHours(1))).isNull();
        assertThat(service.automaticStopReason(member, resolved.plusHours(2))).contains("有效期");
        ticket.setTicketStatus(TicketStatus.OPEN);
        assertThat(service.automaticStopReason(member, resolved.plusHours(1))).contains("办结状态");
        ticket.setTicketStatus(TicketStatus.RESOLVED);
        when(policies.contact(any(), eq(1L))).thenReturn(null);
        assertThat(service.automaticStopReason(member, resolved.plusHours(1))).contains("撤销");
    }

    @Test
    void blacklistedAutomaticCallbackRecordsSkipInsteadOfStartingTask() {
        LocalDateTime resolved = LocalDateTime.of(2026, 11, 12, 14, 0);
        prepareAutomatic(resolved);
        when(blacklist.check(7L, "13800138000")).thenReturn(new OutboundBlacklistMatch());
        service.enqueueAutomatic(1L, resolved.plusHours(1));
        var captor = ArgumentCaptor.forClass(OutboundMember.class);
        verify(members).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SKIPPED");
        assertThat(captor.getValue().getCompletionReason()).contains("黑名单");
        verify(tasks, never()).update(any(), any());
    }

    @Test
    void policyIsReadAndSavedByTemplateIdWithoutAnExistingTicket() {
        var template = new FormTemplateResponse();
        template.setBusinessType(FormBusinessType.TICKET);
        when(templates.get(9L)).thenReturn(template);
        assertThat(service.settings(9L).mode()).isEqualTo("MANUAL");

        service.savePolicy(9L, new TicketCallbackService.PolicyRequest("OFF", null, 30, 1440, null));

        var saved = ArgumentCaptor.forClass(TicketCallbackPolicyMapper.Policy.class);
        verify(policies).savePolicy(any(), eq(9L), saved.capture());
        assertThat(saved.getValue().mode()).isEqualTo("OFF");
        assertThat(saved.getValue().allowInboundCaller()).isFalse();
        verifyNoInteractions(tickets, ticketMapper);
    }

    @Test
    void customerTemplateCannotReadOrChangeCallbackPolicy() {
        var template = new FormTemplateResponse();
        template.setBusinessType(FormBusinessType.CUSTOMER);
        when(templates.get(9L)).thenReturn(template);

        assertThatThrownBy(() -> service.settings(9L)).hasMessageContaining("工单模板");
        assertThatThrownBy(() -> service.savePolicy(9L, new TicketCallbackService.PolicyRequest("OFF", null, 30, 1440, null)))
            .hasMessageContaining("工单模板");
        verifyNoInteractions(policies);
    }

    @Test
    void readOnlyModeUsesTheReadableTicketWithoutLoadingDialingOptions() {
        var ticket = new TicketResponse();
        ticket.setTemplateId(9L);
        when(tickets.get(1L)).thenReturn(ticket);
        when(policies.policy(any(), eq(9L))).thenReturn(new TicketCallbackPolicyMapper.Policy(
            "AUTO", 7L, 30, 120, "policy-1", LocalDateTime.now(), false));

        assertThat(service.mode(1L).mode()).isEqualTo("AUTO");
        verify(tickets).get(1L);
        verifyNoInteractions(tasks, templates, members);
    }

    @Test
    void originalCallerIsUnavailableToAutomaticCallbackByDefault() {
        LocalDateTime resolved = LocalDateTime.of(2026, 11, 12, 14, 0);
        prepareInbound(resolved, false);

        assertThat(service.options(1L).automaticPhones()).isEmpty();
        assertThatThrownBy(() -> service.confirmContact(1L, new TicketCallbackService.ContactRequest("13900139000", true)))
            .hasMessageContaining("不在当前自动回访策略");
        verifyNoInteractions(calls);
    }

    @Test
    void verifiedInboundCallerRequiresConfirmationAndIsRecheckedBeforeDialing() {
        LocalDateTime resolved = LocalDateTime.of(2026, 11, 12, 14, 0);
        CallSession call = prepareInbound(resolved, true);
        assertThat(service.options(1L).automaticPhones()).extracting(TicketCallbackService.PhoneOption::phone)
            .containsExactly("13900139000");
        service.enqueueAutomatic(1L, resolved.plusHours(1));
        verify(members, never()).insert(any(OutboundMember.class));

        service.confirmContact(1L, new TicketCallbackService.ContactRequest("13900139000", true));
        var contact = ArgumentCaptor.forClass(TicketCallbackPolicyMapper.Contact.class);
        verify(policies).saveContact(any(), eq(1L), contact.capture());
        when(policies.contact(any(), eq(1L))).thenReturn(contact.getValue());
        service.enqueueAutomatic(1L, resolved.plusHours(1));

        var member = ArgumentCaptor.forClass(OutboundMember.class);
        verify(members).insert(member.capture());
        assertThat(member.getValue().getPhoneNumber()).isEqualTo("13900139000");
        assertThat(service.automaticStopReason(member.getValue(), resolved.plusHours(1))).isNull();
        call.setCallerNumber("13700137000");
        assertThat(service.automaticStopReason(member.getValue(), resolved.plusHours(1))).contains("变更");
    }

    @Test
    void outboundOrMissingSourceCallCannotBeConfirmedAsOriginalInboundCaller() {
        LocalDateTime resolved = LocalDateTime.of(2026, 11, 12, 14, 0);
        CallSession call = prepareInbound(resolved, true);
        call.setDirection("OUTBOUND");
        assertThat(service.options(1L).automaticPhones()).isEmpty();
        assertThatThrownBy(() -> service.confirmContact(1L, new TicketCallbackService.ContactRequest("13900139000", true)))
            .hasMessageContaining("不在当前自动回访策略");
        when(calls.selectOne(any())).thenReturn(null);
        assertThat(service.options(1L).automaticPhones()).isEmpty();
    }

    @Test
    void togglingCallerPermissionChangesPolicyRevision() {
        LocalDateTime resolved = LocalDateTime.of(2026, 11, 12, 14, 0);
        prepareInbound(resolved, true);
        service.savePolicy(9L, new TicketCallbackService.PolicyRequest("AUTO", 7L, 30, 120, false));
        var saved = ArgumentCaptor.forClass(TicketCallbackPolicyMapper.Policy.class);
        verify(policies).savePolicy(any(), eq(9L), saved.capture());
        assertThat(saved.getValue().allowInboundCaller()).isFalse();
        assertThat(saved.getValue().revision()).isNotEqualTo("policy-1");
    }

    private CallSession prepareInbound(LocalDateTime resolved, boolean allowInboundCaller) {
        Ticket ticket = prepareAutomatic(resolved);
        ticket.setSourceCallId("source-call-1");
        ticket.setCallerNumber("13900139000");
        var readable = new TicketResponse();
        readable.setTemplateId(ticket.getTemplateId());
        readable.setCallerNumber(ticket.getCallerNumber());
        readable.setSourceCallId(ticket.getSourceCallId());
        when(tickets.get(1L)).thenReturn(readable);
        when(policies.policy(any(), eq(9L))).thenReturn(new TicketCallbackPolicyMapper.Policy(
            "AUTO", 7L, 30, 120, "policy-1", resolved.minusMinutes(1), allowInboundCaller));
        when(formData.getFormData(FormBusinessType.TICKET, 1L)).thenReturn(Map.of());
        when(policies.contact(any(), eq(1L))).thenReturn(null);
        var call = new CallSession();
        call.setDirection("INBOUND");
        call.setCallerNumber("139-0013-9000");
        when(calls.selectOne(any())).thenReturn(call);
        return call;
    }

    private Ticket prepareAutomatic(LocalDateTime resolved) {
        prepare();
        var ticket = new Ticket();
        ticket.setId(1L);
        ticket.setTemplateId(9L);
        ticket.setTicketStatus(TicketStatus.RESOLVED);
        ticket.setResolvedAt(Date.from(resolved.atZone(ZoneId.systemDefault()).toInstant()));
        when(ticketMapper.selectOne(any())).thenReturn(ticket);
        when(ticketMapper.selectById(1L)).thenReturn(ticket);
        when(policies.policy(any(), eq(9L))).thenReturn(new TicketCallbackPolicyMapper.Policy(
            "AUTO", 7L, 30, 120, "policy-1", resolved.minusMinutes(1), false));
        when(policies.contact(any(), eq(1L))).thenReturn(new TicketCallbackPolicyMapper.Contact("13800138000", "contact-1"));
        var template = new FormTemplateResponse();
        template.setBusinessType(FormBusinessType.TICKET);
        template.setEnabled(true);
        var field = new FormTemplateResponse.FieldResponse();
        field.setFieldName("回访电话");
        field.setFieldCode("callback_phone");
        field.setEnabled(true);
        template.setFields(List.of(field));
        when(templates.get(9L)).thenReturn(template);
        when(formData.getFormData(FormBusinessType.TICKET, 1L)).thenReturn(Map.of("callback_phone", "13800138000"));
        return ticket;
    }

    private void prepare() {
        var readable = new TicketResponse();
        readable.setCallerNumber("13800138000");
        when(tickets.get(any())).thenReturn(readable);
        var ticket = new Ticket();
        ticket.setTicketStatus(TicketStatus.RESOLVED);
        var second = new Ticket();
        second.setId(2L);
        second.setTicketStatus(TicketStatus.RESOLVED);
        ticket.setId(1L);
        when(ticketMapper.selectOne(any())).thenReturn(ticket, second);
        var task = new OutboundTask();
        task.setId(7L);
        task.setStatus("RUNNING");
        when(tasks.selectOne(any())).thenReturn(task);
    }
}
