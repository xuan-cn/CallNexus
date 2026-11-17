package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.response.FormTemplateResponse;
import org.dromara.customer.form.service.DynamicFormSubmissionService;
import org.dromara.customer.form.service.FormTemplateApplicationService;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketStatus;
import org.dromara.customer.ticket.domain.response.TicketResponse;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.customer.ticket.service.TicketApplicationService;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.OutboundTaskSource;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.mapper.OutboundTaskSourceMapper;
import org.dromara.outbound.mapper.TicketCallbackPolicyMapper;
import org.dromara.outbound.mapper.TicketCallbackPolicyMapper.Policy;
import org.dromara.outbound.mapper.TicketCallbackPolicyMapper.Contact;
import org.dromara.outbound.service.OutboundBlacklistChecker;
import org.dromara.outbound.service.PhoneNumberNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketCallbackService {
    public static final String SOURCE = "TICKET_CALLBACK";
    private static final Set<String> ENQUEUE_STATES = Set.of("DRAFT", "RUNNING", "COMPLETED");
    private static final Set<String> ACTIVE = Set.of("PENDING", "RETRY", "SCHEDULED", "CLAIMED", "DIALING");
    private final TicketApplicationService tickets;
    private final TicketMapper ticketMapper;
    private final CustomerApplicationService customers;
    private final FormTemplateApplicationService templates;
    private final OutboundTaskMapper tasks;
    private final OutboundTaskSourceMapper sources;
    private final OutboundMemberMapper members;
    private final PhoneNumberNormalizer phones;
    private final OutboundBlacklistChecker blacklist;
    private final TicketCallbackPolicyMapper policies;
    private final DynamicFormSubmissionService formData;
    private final CallSessionMapper calls;

    public Options options(Long ticketId) {
        TicketResponse ticket = tickets.get(ticketId);
        Policy policy = policy(ticket.getTemplateId());
        Contact contact = policies.contact(TenantHelper.getTenantId(), ticketId);
        return new Options(candidates(ticket), eligibleTasks(),
            automaticPhones(ticket.getTemplateId(), ticket.getSourceCallId(), ticket.getCallerNumber(), ticket.getFormData(), policy),
            contact == null ? null : contact.phone(), modeOf(policy), allowsInboundCaller(policy));
    }

    public CallbackMode mode(Long ticketId) {
        return new CallbackMode(modeOf(policy(tickets.get(ticketId).getTemplateId())));
    }

    public List<History> history(Long ticketId) {
        tickets.get(ticketId);
        return members.selectList(callbacks(ticketId).orderByDesc(OutboundMember::getId).last("LIMIT 100"))
            .stream().map(member -> new History(member.getId().toString(), member.getPhoneNumber(), member.getStatus(),
                member.getResultCode(), member.getBusinessCallId(), member.getAttemptCount(),
                member.getBlockedReason() != null ? member.getBlockedReason()
                    : member.getResultRemark() != null ? member.getResultRemark() : member.getCompletionReason(),
                member.getCreateTime() == null ? null : member.getCreateTime().toInstant().toString(),
                member.getCallbackPolicyRevision() != null)).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long enqueue(Long ticketId, Request request) {
        TicketResponse readable = tickets.get(ticketId);
        Ticket ticket = lockTicket(ticketId);
        if (ticket == null) throw new ServiceException("工单不存在");
        requireResolved(ticket.getTicketStatus());
        Policy policy = policy(ticket.getTemplateId());
        if (policy != null && "OFF".equals(policy.mode())) throw new ServiceException("该工单模板已关闭回访");
        if (existingCallback(ticketId, null)) {
            throw new ServiceException("该工单已有待拨或进行中的回访");
        }
        if (!request.confirmed()) throw new ServiceException("请先确认该号码可用于本工单回访");
        String phone = phones.normalize(request.phone());
        if (candidates(readable).stream().noneMatch(item -> item.phone().equals(phone))) {
            throw new ServiceException("该号码不在当前工单的可选号码中，请刷新后重试");
        }
        OutboundTask task = lockTask(request.taskId());
        if (task == null) throw new ServiceException("请选择没有普通名单或资料来源的 AI 自动外呼任务");
        if (blacklist.check(task.getId(), phone) != null) throw new ServiceException("号码命中外呼黑名单，未发起回访");
        return insertMember(ticket, task, phone, null, null, false);
    }

    public PolicySettings settings(Long templateId) {
        requireTicketTemplate(templateId);
        Policy policy = policy(templateId);
        return new PolicySettings(modeOf(policy),
            policy == null || policy.taskId() == null ? null : policy.taskId().toString(),
            policy == null ? 30 : policy.delayMinutes(), policy == null ? 1440 : policy.expiryMinutes(),
            allowsInboundCaller(policy), eligibleTasks());
    }

    @Transactional(rollbackFor = Exception.class)
    public void savePolicy(Long templateId, PolicyRequest request) {
        FormTemplateResponse template = requireTicketTemplate(templateId);
        if (request.expiryMinutes() <= request.delayMinutes()) throw new ServiceException("有效期必须大于办结后的等待时间");
        boolean automatic = "AUTO".equals(request.mode());
        Long taskId = automatic ? request.taskId() : null;
        if (automatic) {
            if (!Boolean.TRUE.equals(template.getEnabled())) throw new ServiceException("工单模板未启用");
            if (taskId == null || lockTask(taskId) == null) throw new ServiceException("请选择专用 AI 自动外呼任务");
        }
        Policy current = policy(templateId);
        boolean allowInboundCaller = automatic && (request.allowInboundCaller() == null
            ? allowsInboundCaller(current) : request.allowInboundCaller());
        if (current != null && current.mode().equals(request.mode()) && Objects.equals(current.taskId(), taskId)
            && current.delayMinutes() == request.delayMinutes() && current.expiryMinutes() == request.expiryMinutes()
            && current.allowInboundCaller() == allowInboundCaller) return;
        policies.savePolicy(TenantHelper.getTenantId(), templateId, new Policy(request.mode(), taskId,
            request.delayMinutes(), request.expiryMinutes(), UUID.randomUUID().toString(), LocalDateTime.now(), allowInboundCaller));
    }

    private FormTemplateResponse requireTicketTemplate(Long templateId) {
        FormTemplateResponse template = templates.get(templateId);
        if (template.getBusinessType() != FormBusinessType.TICKET) {
            throw new ServiceException("只有工单模板可以配置回访策略");
        }
        return template;
    }

    private String modeOf(Policy policy) {
        return policy == null ? "MANUAL" : policy.mode();
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmContact(Long ticketId, ContactRequest request) {
        tickets.get(ticketId);
        Ticket ticket = lockTicket(ticketId);
        if (ticket == null) throw new ServiceException("工单不存在");
        String tenant = TenantHelper.getTenantId();
        if (!Boolean.TRUE.equals(request.confirmed())) {
            policies.revokeContact(tenant, ticketId);
            return;
        }
        String phone = phones.normalize(request.phone());
        if (!isAutomaticPhone(ticket, phone, policy(ticket.getTemplateId()))) {
            throw new ServiceException("该号码不在当前自动回访策略允许的范围内，请刷新后重试");
        }
        Contact current = policies.contact(tenant, ticketId);
        if (current == null || !current.phone().equals(phone)) {
            policies.saveContact(tenant, ticketId, new Contact(phone, UUID.randomUUID().toString()));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void enqueueAutomatic(Long ticketId, LocalDateTime now) {
        Ticket ticket = lockTicket(ticketId);
        if (ticket == null || !resolved(ticket.getTicketStatus()) || ticket.getResolvedAt() == null) return;
        LocalDateTime cycle = cycle(ticket);
        Policy policy = policy(ticket.getTemplateId());
        if (policy == null || !"AUTO".equals(policy.mode()) || !cycle.isAfter(policy.enabledSince())
            || now.isBefore(cycle.plusMinutes(policy.delayMinutes())) || !now.isBefore(cycle.plusMinutes(policy.expiryMinutes()))
            || existingCallback(ticketId, cycle)) return;
        Contact contact = policies.contact(TenantHelper.getTenantId(), ticketId);
        if (contact == null) return;
        if (!isAutomaticPhone(ticket, contact.phone(), policy)) {
            policies.revokeContact(TenantHelper.getTenantId(), ticketId);
            return;
        }
        OutboundTask task = lockTask(policy.taskId());
        if (task == null) return;
        insertMember(ticket, task, contact.phone(), policy, contact, blacklist.check(task.getId(), contact.phone()) != null);
    }

    public String automaticStopReason(OutboundMember member, LocalDateTime now) {
        if (member.getCallbackPolicyRevision() == null) return null;
        Ticket ticket = ticketMapper.selectById(member.getSourceId());
        if (ticket == null || !resolved(ticket.getTicketStatus()) || !Objects.equals(cycle(ticket), member.getCallbackResolvedAt())) {
            return "工单不再处于本次办结状态";
        }
        Policy policy = policy(ticket.getTemplateId());
        if (policy == null || !"AUTO".equals(policy.mode()) || !member.getCallbackPolicyRevision().equals(policy.revision())) {
            return "自动回访策略已调整或关闭";
        }
        if (!now.isBefore(member.getCallbackResolvedAt().plusMinutes(policy.expiryMinutes()))) return "自动回访已过有效期";
        Contact contact = policies.contact(TenantHelper.getTenantId(), ticket.getId());
        if (contact == null || !contact.revision().equals(member.getCallbackContactRevision())
            || !contact.phone().equals(member.getPhoneNumber()) || !isAutomaticPhone(ticket, contact.phone(), policy)) {
            return "回访号码确认已撤销或号码已变更";
        }
        return null;
    }

    private Long insertMember(Ticket ticket, OutboundTask task, String phone, Policy policy, Contact contact, boolean blocked) {
        OutboundMember member = new OutboundMember();
        member.setTaskId(task.getId());
        member.setCustomerId(ticket.getCustomerId());
        member.setCustomerName(ticket.getTicketNo());
        member.setPhoneNumber(phone);
        member.setSourceType(SOURCE);
        member.setSourceId(ticket.getId());
        member.setCallbackResolvedAt(cycle(ticket));
        member.setStatus(blocked ? "SKIPPED" : "PENDING");
        if (policy != null) {
            member.setCallbackPolicyRevision(policy.revision());
            member.setCallbackContactRevision(contact.revision());
        }
        if (blocked) {
            member.setCompletionReason("号码命中外呼黑名单，未发起回访");
            member.setCompletedAt(LocalDateTime.now());
        }
        member.setAttemptCount(0);
        members.insert(member);
        if (!blocked && !"RUNNING".equals(task.getStatus())) {
            tasks.update(null, new LambdaUpdateWrapper<OutboundTask>().eq(OutboundTask::getId, task.getId())
                .set(OutboundTask::getStatus, "RUNNING")
                .set(task.getExecutionStartedAt() == null, OutboundTask::getExecutionStartedAt, LocalDateTime.now()));
        }
        return member.getId();
    }

    private List<PhoneOption> candidates(TicketResponse ticket) {
        Map<String, PhoneOption> result = new LinkedHashMap<>();
        formPhones(ticket.getTemplateId(), ticket.getFormData()).forEach(item -> result.put(item.phone(), item));
        if (ticket.getCustomerId() != null) {
            try {
                addPhone(result, customers.get(ticket.getCustomerId()).getPrimaryPhone(), "客户主号码");
            } catch (ServiceException ignored) {
                // Ticket visibility does not grant access to the customer profile.
            }
        }
        addPhone(result, ticket.getCallerNumber(), "原通话号码，可能是代报人");
        return List.copyOf(result.values());
    }

    private List<PhoneOption> formPhones(Long templateId, Map<String, Object> values) {
        return templateId == null || values == null || values.isEmpty()
            ? List.of() : formPhones(templates.get(templateId), values);
    }

    private List<PhoneOption> formPhones(FormTemplateResponse template, Map<String, Object> values) {
        Map<String, PhoneOption> result = new LinkedHashMap<>();
        if (values != null && !values.isEmpty()) {
            template.getFields().stream().filter(field -> Boolean.TRUE.equals(field.getEnabled()))
                .filter(field -> "回访联系电话".equals(field.getFieldName()) || "回访电话".equals(field.getFieldName()))
                .forEach(field -> {
                    Object value = values.get(field.getFieldCode());
                    if (value != null) addPhone(result, String.valueOf(value), "工单：" + field.getFieldName());
                });
        }
        return List.copyOf(result.values());
    }

    private List<PhoneOption> automaticPhones(Long templateId, String sourceCallId, String ticketCaller,
                                               Map<String, Object> values, Policy policy) {
        if (templateId == null || !"AUTO".equals(modeOf(policy))) return List.of();
        FormTemplateResponse template = templates.get(templateId);
        if (!Boolean.TRUE.equals(template.getEnabled()) || template.getBusinessType() != FormBusinessType.TICKET) return List.of();
        List<PhoneOption> formOptions = formPhones(template, values);
        if (!allowsInboundCaller(policy) || sourceCallId == null || sourceCallId.isBlank()) return formOptions;
        String normalizedCaller = phones.normalize(ticketCaller);
        if (!phones.isValid(normalizedCaller) || formOptions.stream().anyMatch(item -> item.phone().equals(normalizedCaller))) {
            return formOptions;
        }
        CallSession call = calls.selectOne(new LambdaQueryWrapper<CallSession>()
            .select(CallSession::getDirection, CallSession::getCallerNumber)
            .eq(CallSession::getBusinessCallId, sourceCallId));
        if (call == null || !"INBOUND".equals(call.getDirection())
            || !normalizedCaller.equals(phones.normalize(call.getCallerNumber()))) return formOptions;
        var options = new ArrayList<>(formOptions);
        options.add(new PhoneOption(normalizedCaller, "原来电号码，可能是代报人"));
        return List.copyOf(options);
    }

    private boolean allowsInboundCaller(Policy policy) {
        return policy != null && "AUTO".equals(policy.mode()) && policy.allowInboundCaller();
    }

    private boolean isAutomaticPhone(Ticket ticket, String phone, Policy policy) {
        if (ticket.getTemplateId() == null || !"AUTO".equals(modeOf(policy)) || !phones.isValid(phone)) return false;
        try {
            return automaticPhones(ticket.getTemplateId(), ticket.getSourceCallId(), ticket.getCallerNumber(),
                formData.getFormData(FormBusinessType.TICKET, ticket.getId()), policy)
                .stream().anyMatch(item -> item.phone().equals(phone));
        } catch (ServiceException ignored) {
            return false;
        }
    }

    private List<TaskOption> eligibleTasks() {
        return tasks.selectList(taskQuery().orderByDesc(OutboundTask::getId)).stream().filter(task -> canUseTask(task.getId()))
            .map(task -> new TaskOption(task.getId().toString(), task.getTaskName(), task.getStatus())).toList();
    }

    private Policy policy(Long templateId) {
        return templateId == null ? null : policies.policy(TenantHelper.getTenantId(), templateId);
    }

    private Ticket lockTicket(Long ticketId) {
        return ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>().eq(Ticket::getId, ticketId).last("FOR UPDATE"));
    }

    private OutboundTask lockTask(Long taskId) {
        OutboundTask task = tasks.selectOne(taskQuery().eq(OutboundTask::getId, taskId).last("FOR UPDATE"));
        return task != null && canUseTask(task.getId()) ? task : null;
    }

    private boolean existingCallback(Long ticketId, LocalDateTime cycle) {
        return members.selectOne(callbacks(ticketId).and(q -> {
            q.in(OutboundMember::getStatus, ACTIVE);
            if (cycle != null) q.or().eq(OutboundMember::getCallbackResolvedAt, cycle);
        }).last("LIMIT 1 FOR UPDATE")) != null;
    }

    private LocalDateTime cycle(Ticket ticket) {
        return ticket.getResolvedAt() == null ? null : LocalDateTime.ofInstant(
            ticket.getResolvedAt().toInstant(), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS);
    }

    private void addPhone(Map<String, PhoneOption> result, String raw, String source) {
        String phone = phones.normalize(raw);
        if (phones.isValid(phone)) result.putIfAbsent(phone, new PhoneOption(phone, source));
    }

    private LambdaQueryWrapper<OutboundTask> taskQuery() {
        return new LambdaQueryWrapper<OutboundTask>().eq(OutboundTask::getTaskType, "AUTO")
            .eq(OutboundTask::getDialMode, "AGENTLESS_AI").isNotNull(OutboundTask::getTargetId)
            .in(OutboundTask::getStatus, ENQUEUE_STATES);
    }

    private boolean canUseTask(Long taskId) {
        return sources.selectCount(new LambdaQueryWrapper<OutboundTaskSource>().eq(OutboundTaskSource::getTaskId, taskId)) == 0
            && members.selectCount(new LambdaQueryWrapper<OutboundMember>().eq(OutboundMember::getTaskId, taskId)
                .ne(OutboundMember::getSourceType, SOURCE)) == 0;
    }

    private LambdaQueryWrapper<OutboundMember> callbacks(Long ticketId) {
        return new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getSourceType, SOURCE).eq(OutboundMember::getSourceId, ticketId);
    }

    private void requireResolved(TicketStatus status) {
        if (!resolved(status)) throw new ServiceException("只允许回访已办结或已关闭的工单");
    }

    private boolean resolved(TicketStatus status) {
        return status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED;
    }

    public record Request(@NotNull Long taskId, @NotBlank String phone, boolean confirmed) {}
    public record ContactRequest(String phone, @NotNull Boolean confirmed) {}
    public record PolicyRequest(@NotBlank @Pattern(regexp = "OFF|MANUAL|AUTO") String mode, Long taskId,
                                @Min(0) @Max(10080) int delayMinutes, @Min(1) @Max(43200) int expiryMinutes,
                                Boolean allowInboundCaller) {}
    public record PolicySettings(String mode, String taskId, int delayMinutes, int expiryMinutes, boolean allowInboundCaller,
                                 List<TaskOption> tasks) {}
    public record CallbackMode(String mode) {}
    public record Options(List<PhoneOption> phones, List<TaskOption> tasks, List<PhoneOption> automaticPhones,
                          String confirmedPhone, String mode, boolean allowInboundCaller) {}
    public record PhoneOption(String phone, String source) {}
    public record TaskOption(String id, String name, String status) {}
    public record History(String id, String phone, String status, String resultCode, String businessCallId,
                          Integer attemptCount, String remark, String createdAt, boolean automatic) {}
}
