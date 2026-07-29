package org.dromara.customer.ticket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.event.ProcessEvent;
import org.dromara.common.core.domain.event.ProcessTaskEvent;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketStatus;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Keeps ticket business state synchronized with workflow lifecycle events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketWorkflowEventHandler {

    private final TicketMapper ticketMapper;

    @EventListener
    public void handleProcessEvent(ProcessEvent event) {
        Ticket ticket = findTicket(event.getBusinessId(), event.getFlowCode());
        if (ticket == null) {
            return;
        }
        ticket.setFlowInstanceId(event.getInstanceId());
        ticket.setProcessStatus(event.getStatus());
        ticket.setCurrentNodeCode(event.getNodeCode());
        ticket.setCurrentNodeName(event.getNodeName());
        if (Boolean.TRUE.equals(event.getSubmit())) {
            ticket.setProcessStatus(BusinessStatusEnum.WAITING.getStatus());
            ticket.setTicketStatus(TicketStatus.PROCESSING);
        } else {
            applyBusinessStatus(ticket, event.getStatus());
        }
        ticketMapper.updateById(ticket);
        log.info("工单流程状态已同步，ticketId={}，flowCode={}，processStatus={}，ticketStatus={}，nodeCode={}",
            ticket.getId(), event.getFlowCode(), event.getStatus(), ticket.getTicketStatus(), event.getNodeCode());
    }

    @EventListener
    public void handleTaskEvent(ProcessTaskEvent event) {
        Ticket ticket = findTicket(event.getBusinessId(), event.getFlowCode());
        if (ticket == null) {
            return;
        }
        ticket.setFlowInstanceId(event.getInstanceId());
        ticket.setProcessStatus(event.getStatus());
        ticket.setCurrentNodeCode(event.getNodeCode());
        ticket.setCurrentNodeName(event.getNodeName());
        if (ticket.getTicketStatus() == TicketStatus.OPEN) {
            ticket.setTicketStatus(TicketStatus.PROCESSING);
        }
        ticketMapper.updateById(ticket);
        log.info("工单当前流程任务已同步，ticketId={}，flowCode={}，taskId={}，nodeCode={}，nodeName={}",
            ticket.getId(), event.getFlowCode(), event.getTaskId(), event.getNodeCode(), event.getNodeName());
    }

    private Ticket findTicket(String businessId, String flowCode) {
        if (businessId == null || flowCode == null) {
            return null;
        }
        final Long ticketId;
        try {
            ticketId = Long.valueOf(businessId);
        } catch (NumberFormatException ignored) {
            return null;
        }
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || !flowCode.equals(ticket.getWorkflowCode())) {
            return null;
        }
        return ticket;
    }

    private void applyBusinessStatus(Ticket ticket, String processStatus) {
        if (BusinessStatusEnum.FINISH.getStatus().equals(processStatus)) {
            ticket.setTicketStatus(TicketStatus.RESOLVED);
            ticket.setResolvedAt(new Date());
            return;
        }
        if (BusinessStatusEnum.INVALID.getStatus().equals(processStatus)
            || BusinessStatusEnum.TERMINATION.getStatus().equals(processStatus)) {
            ticket.setTicketStatus(TicketStatus.CLOSED);
            ticket.setClosedAt(new Date());
            return;
        }
        if (BusinessStatusEnum.BACK.getStatus().equals(processStatus)
            || BusinessStatusEnum.CANCEL.getStatus().equals(processStatus)
            || BusinessStatusEnum.DRAFT.getStatus().equals(processStatus)) {
            ticket.setTicketStatus(TicketStatus.OPEN);
            return;
        }
        ticket.setTicketStatus(TicketStatus.PROCESSING);
    }
}
