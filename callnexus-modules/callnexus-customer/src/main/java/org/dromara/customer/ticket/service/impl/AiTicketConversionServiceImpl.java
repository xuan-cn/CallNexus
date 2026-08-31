package org.dromara.customer.ticket.service.impl;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.agent.domain.SkillGroupMember;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.mapper.SkillGroupMemberMapper;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.ai.service.AiTicketConversionService;
import org.dromara.customer.customer.domain.request.CreateCustomerRequest;
import org.dromara.customer.customer.domain.request.CustomerAssignmentRequest;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.ticket.domain.request.CreateTicketRequest;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.customer.ticket.service.TicketApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class AiTicketConversionServiceImpl implements AiTicketConversionService {
    private final CustomerApplicationService customerService;
    private final TicketApplicationService ticketService;
    private final CurrentAgentSessionService currentAgentSessionService;
    private final SkillGroupMemberMapper skillGroupMemberMapper;
    private final TicketMapper ticketMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long convert(Command command) {
        Long customerId = command.customerId();
        boolean assignToReviewer = false;
        if (customerId != null) {
            CustomerResponse existing = customerService.get(customerId);
            assignToReviewer = existing.getAssignmentId() == null;
        }
        if (customerId == null && command.callerNumber() != null && !command.callerNumber().isBlank()) {
            CustomerResponse existing = customerService.getByPhone(command.callerNumber());
            if (existing != null) {
                customerId = existing.getId();
                assignToReviewer = existing.getAssignmentId() == null;
            } else {
                CreateCustomerRequest customer = new CreateCustomerRequest();
                customer.setPrimaryPhone(command.callerNumber());
                customer.setCustomerName("未命名客户");
                customer.setSourceCallId(command.sourceCallId());
                customer.setTemplateId(command.customerTemplateId());
                customerId = customerService.create(customer);
                assignToReviewer = true;
            }
        }
        if (assignToReviewer) assignCustomer(customerId, command);
        CreateTicketRequest ticket = new CreateTicketRequest();
        ticket.setCustomerId(customerId);
        ticket.setCallerNumber(command.callerNumber());
        ticket.setSourceCallId(command.sourceCallId());
        ticket.setTemplateId(command.ticketTemplateId());
        ticket.setSourceType(switch (command.conversionMode() == null ? "REVIEW" : command.conversionMode()) {
            case "AUTO" -> "AI_AUTO";
            case "INTENT" -> "AI_INTENT";
            default -> "AI_REVIEW";
        });
        ticket.setSourceDraftId(command.draftId());
        ticket.setAiAgentId(command.aiAgentId());
        ticket.setFormData(command.formData());
        Long ticketId = ticketService.create(ticket);
        String action = command.afterCreateAction() == null ? "CREATE_ONLY" : command.afterCreateAction();
        if ("SUBMIT".equals(action)) {
            ticketService.submit(ticketId);
        } else if ("RESOLVE".equals(action)) {
            ticketService.resolveDirectly(ticketId);
        }
        return ticketId;
    }

    @Override
    public Long findDuplicateTicket(Long customerId, String callerNumber, Long ticketTemplateId,
                                    Integer windowHours) {
        Long resolvedCustomerId = customerId;
        if (resolvedCustomerId == null && callerNumber != null && !callerNumber.isBlank()) {
            CustomerResponse customer = customerService.getByPhone(callerNumber);
            resolvedCustomerId = customer == null ? null : customer.getId();
        }
        if (resolvedCustomerId == null || ticketTemplateId == null) return null;
        int hours = windowHours == null ? 24 : Math.max(1, windowHours);
        Date threshold = Date.from(LocalDateTime.now().minusHours(hours)
            .atZone(ZoneId.systemDefault()).toInstant());
        Ticket duplicate = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
            .eq(Ticket::getCustomerId, resolvedCustomerId)
            .eq(Ticket::getTemplateId, ticketTemplateId)
            .ge(Ticket::getCreateTime, threshold)
            .orderByDesc(Ticket::getCreateTime)
            .last("LIMIT 1"));
        return duplicate == null ? null : duplicate.getId();
    }

    private void assignCustomer(Long customerId, Command command) {
        if (customerId == null) return;
        if ("AUTO".equals(command.conversionMode())) {
            if (command.defaultSkillGroupId() == null) return;
            CustomerAssignmentRequest assignment = new CustomerAssignmentRequest();
            assignment.setCustomerIds(List.of(customerId));
            assignment.setSkillGroupId(command.defaultSkillGroupId());
            assignment.setRemark("AI 自动工单归属");
            customerService.assign(assignment);
            return;
        }
        CurrentAgentResponse current = currentAgentSessionService.current();
        if (current == null || !current.isConfigured() || current.getAgentId() == null) return;
        SkillGroupMember membership = skillGroupMemberMapper.selectOne(new LambdaQueryWrapper<SkillGroupMember>()
            .eq(SkillGroupMember::getAgentId, current.getAgentId())
            .orderByAsc(SkillGroupMember::getPriority).orderByAsc(SkillGroupMember::getId).last("LIMIT 1"));
        if (membership == null) return;
        CustomerAssignmentRequest assignment = new CustomerAssignmentRequest();
        assignment.setCustomerIds(List.of(customerId));
        assignment.setSkillGroupId(membership.getSkillGroupId());
        assignment.setAgentId(current.getAgentId());
        assignment.setRemark("AI 工单审核归属");
        customerService.assign(assignment);
    }
}
