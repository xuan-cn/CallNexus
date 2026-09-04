package org.dromara.outbound.home;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.VoiceMailMessage;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.mapper.VoiceMailMessageMapper;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.customer.customer.domain.Customer;
import org.dromara.customer.customer.domain.CustomerAssignment;
import org.dromara.customer.customer.mapper.CustomerAssignmentMapper;
import org.dromara.customer.customer.mapper.CustomerMapper;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketStatus;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.outbound.domain.response.AutoOutboundTaskResponse;
import org.dromara.outbound.domain.response.OutboundTaskResponse;
import org.dromara.outbound.service.AutoOutboundTaskService;
import org.dromara.outbound.service.OutboundTaskService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 系统首页业务概览聚合：客户、工单、呼入呼出。
 */
@Service
@RequiredArgsConstructor
public class HomeBusinessOverviewService {

    private final CustomerMapper customerMapper;
    private final CustomerAssignmentMapper customerAssignmentMapper;
    private final TicketMapper ticketMapper;
    private final CallSessionMapper callSessionMapper;
    private final VoiceMailMessageMapper voiceMailMessageMapper;
    private final OutboundTaskService outboundTaskService;
    private final AutoOutboundTaskService autoOutboundTaskService;

    public HomeBusinessOverviewResponse overview(String beginDate, String endDate) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate begin = parseDate(beginDate, LocalDate.now());
        LocalDate end = parseDate(endDate, begin);
        if (end.isBefore(begin)) {
            end = begin;
        }
        LocalDateTime rangeStart = begin.atStartOfDay();
        LocalDateTime rangeEndExclusive = end.plusDays(1).atStartOfDay();
        Date rangeStartDate = Date.from(rangeStart.atZone(zone).toInstant());
        Date rangeEndDate = Date.from(rangeEndExclusive.atZone(zone).toInstant());

        HomeBusinessOverviewResponse response = new HomeBusinessOverviewResponse();
        fillCustomer(response, rangeStartDate, rangeEndDate);
        fillTicket(response, rangeStartDate, rangeEndDate);
        fillCall(response, rangeStart, rangeEndExclusive);
        fillOutboundTasks(response);
        fillVoicemail(response);
        return response;
    }

    private void fillCustomer(HomeBusinessOverviewResponse response, Date rangeStart, Date rangeEnd) {
        try {
            long total = nz(customerMapper.selectCount(new LambdaQueryWrapper<>()));
            long periodNew = nz(customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                .ge(Customer::getCreateTime, rangeStart)
                .lt(Customer::getCreateTime, rangeEnd)));
            Set<Long> assignedIds = loadAssignedCustomerIds();
            long unassigned = assignedIds.isEmpty()
                ? total
                : nz(customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                    .notIn(Customer::getId, assignedIds)));
            response.setCustomerTotal(total);
            response.setCustomerPeriodNew(periodNew);
            response.setCustomerUnassigned(Math.max(0, unassigned));
        } catch (Exception ignored) {
            // keep zeros
        }
    }

    private void fillTicket(HomeBusinessOverviewResponse response, Date rangeStart, Date rangeEnd) {
        try {
            long open = countTicket(TicketStatus.OPEN);
            long processing = countTicket(TicketStatus.PROCESSING);
            long resolved = countTicket(TicketStatus.RESOLVED);
            long closed = countTicket(TicketStatus.CLOSED);
            long periodNew = nz(ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .ge(Ticket::getCreateTime, rangeStart)
                .lt(Ticket::getCreateTime, rangeEnd)));
            response.setTicketOpen(open);
            response.setTicketProcessing(processing);
            response.setTicketResolved(resolved);
            response.setTicketClosed(closed);
            response.setTicketTotal(open + processing + resolved + closed);
            response.setTicketPeriodNew(periodNew);
        } catch (Exception ignored) {
            // keep zeros
        }
    }

    private void fillCall(HomeBusinessOverviewResponse response,
                          LocalDateTime rangeStart,
                          LocalDateTime rangeEndExclusive) {
        try {
            long inbound = countSessions(rangeStart, rangeEndExclusive, true);
            long outbound = countSessions(rangeStart, rangeEndExclusive, false);
            long answered = nz(callSessionMapper.selectCount(new LambdaQueryWrapper<CallSession>()
                .ge(CallSession::getStartedAt, rangeStart)
                .lt(CallSession::getStartedAt, rangeEndExclusive)
                .isNotNull(CallSession::getAnsweredAt)));
            response.setInboundCount(inbound);
            response.setOutboundCount(outbound);
            response.setAnsweredCount(answered);
            response.setAnswerRate(inbound <= 0 ? 0 : (int) Math.round(answeredInbound(rangeStart, rangeEndExclusive) * 100.0 / inbound));
        } catch (Exception ignored) {
            // keep zeros
        }
    }

    private long answeredInbound(LocalDateTime rangeStart, LocalDateTime rangeEndExclusive) {
        return nz(callSessionMapper.selectCount(new LambdaQueryWrapper<CallSession>()
            .ge(CallSession::getStartedAt, rangeStart)
            .lt(CallSession::getStartedAt, rangeEndExclusive)
            .isNotNull(CallSession::getAnsweredAt)
            .and(w -> w.eq(CallSession::getDirection, "INBOUND")
                .or()
                .isNull(CallSession::getDirection)
                .or()
                .eq(CallSession::getDirection, ""))));
    }

    private long countSessions(LocalDateTime rangeStart, LocalDateTime rangeEndExclusive, boolean inbound) {
        LambdaQueryWrapper<CallSession> wrapper = new LambdaQueryWrapper<CallSession>()
            .ge(CallSession::getStartedAt, rangeStart)
            .lt(CallSession::getStartedAt, rangeEndExclusive);
        if (inbound) {
            wrapper.and(w -> w.eq(CallSession::getDirection, "INBOUND")
                .or()
                .isNull(CallSession::getDirection)
                .or()
                .eq(CallSession::getDirection, ""));
        } else {
            wrapper.eq(CallSession::getDirection, "OUTBOUND");
        }
        return nz(callSessionMapper.selectCount(wrapper));
    }

    private void fillOutboundTasks(HomeBusinessOverviewResponse response) {
        long total = 0L;
        long completed = 0L;
        try {
            for (OutboundTaskResponse task : outboundTaskService.list()) {
                total += task.getTotalCount();
                completed += task.getCompletedCount();
            }
        } catch (Exception ignored) {
            // ignore
        }
        try {
            for (AutoOutboundTaskResponse task : autoOutboundTaskService.list()) {
                total += task.getTotalCount();
                completed += task.getCompletedCount();
            }
        } catch (Exception ignored) {
            // ignore
        }
        response.setOutboundTaskTotal(total);
        response.setOutboundTaskCompleted(completed);
        response.setOutboundCompletionRate(total <= 0 ? 0 : (int) Math.round(completed * 100.0 / total));
    }

    private void fillVoicemail(HomeBusinessOverviewResponse response) {
        try {
            response.setVoicemailPending(nz(voiceMailMessageMapper.selectCount(new LambdaQueryWrapper<VoiceMailMessage>()
                .eq(VoiceMailMessage::getStatus, "UNHANDLED"))));
        } catch (Exception ignored) {
            response.setVoicemailPending(0L);
        }
    }

    private long countTicket(TicketStatus status) {
        return nz(ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
            .eq(Ticket::getTicketStatus, status)));
    }

    private Set<Long> loadAssignedCustomerIds() {
        List<CustomerAssignment> rows = customerAssignmentMapper.selectList(new LambdaQueryWrapper<CustomerAssignment>()
            .eq(CustomerAssignment::getEnabled, true)
            .select(CustomerAssignment::getCustomerId));
        Set<Long> ids = new HashSet<>();
        for (CustomerAssignment row : rows) {
            if (row.getCustomerId() != null) {
                ids.add(row.getCustomerId());
            }
        }
        return ids;
    }

    private LocalDate parseDate(String raw, LocalDate fallback) {
        if (StringUtils.isBlank(raw)) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }
}
