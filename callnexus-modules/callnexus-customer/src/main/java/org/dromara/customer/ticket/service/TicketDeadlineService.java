package org.dromara.customer.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.sse.dto.SseMessageDto;
import org.dromara.common.sse.utils.SseMessageUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.customer.form.domain.FormTemplate;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketDeadlineStatus;
import org.dromara.customer.ticket.domain.TicketStatus;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketDeadlineService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final TicketMapper ticketMapper;
    private final ScheduledExecutorService scheduledExecutorService;

    @PostConstruct
    public void scheduleScan() {
        scheduledExecutorService.scheduleWithFixedDelay(this::scanSafely, 30, 60, TimeUnit.SECONDS);
    }

    public void initialize(Ticket ticket, FormTemplate template, Date createdAt) {
        if (!Boolean.TRUE.equals(template.getDeadlineEnabled()) || template.getResolutionLimitMinutes() == null) {
            return;
        }
        Date start = createdAt == null ? new Date() : createdAt;
        long limitMillis = TimeUnit.MINUTES.toMillis(template.getResolutionLimitMinutes());
        ticket.setResolutionLimitMinutes(template.getResolutionLimitMinutes());
        ticket.setDueAt(new Date(start.getTime() + limitMillis));
        if (template.getRemindBeforeMinutes() != null && template.getRemindBeforeMinutes() > 0) {
            ticket.setRemindAt(new Date(ticket.getDueAt().getTime()
                - TimeUnit.MINUTES.toMillis(template.getRemindBeforeMinutes())));
        }
        ticket.setDeadlineStatus(TicketDeadlineStatus.NORMAL);
    }

    public void complete(Ticket ticket, Date completedAt) {
        if (ticket.getDueAt() == null) return;
        Date actual = completedAt == null ? new Date() : completedAt;
        boolean overdue = actual.after(ticket.getDueAt());
        ticket.setDeadlineStatus(overdue ? TicketDeadlineStatus.COMPLETED_OVERDUE : TicketDeadlineStatus.COMPLETED);
        if (overdue && ticket.getOverdueAt() == null) ticket.setOverdueAt(actual);
    }

    public void cancel(Ticket ticket) {
        if (ticket.getDueAt() != null) ticket.setDeadlineStatus(TicketDeadlineStatus.CANCELLED);
    }

    public void configure(Ticket ticket, boolean enabled, Date dueAt, Integer remindBeforeMinutes, Date configuredAt) {
        if (!enabled) {
            ticket.setResolutionLimitMinutes(null);
            ticket.setDueAt(null);
            ticket.setRemindAt(null);
            ticket.setDeadlineStatus(null);
            ticket.setDueSoonRemindedAt(null);
            ticket.setOverdueAt(null);
            ticket.setOverdueRemindedAt(null);
            return;
        }
        Date start = configuredAt == null ? new Date() : configuredAt;
        long remainingMillis = Math.max(60_000L, dueAt.getTime() - start.getTime());
        ticket.setResolutionLimitMinutes((int) Math.ceil(remainingMillis / 60_000D));
        ticket.setDueAt(dueAt);
        ticket.setRemindAt(remindBeforeMinutes == null || remindBeforeMinutes <= 0
            ? null : new Date(dueAt.getTime() - TimeUnit.MINUTES.toMillis(remindBeforeMinutes)));
        ticket.setDeadlineStatus(TicketDeadlineStatus.NORMAL);
        ticket.setDueSoonRemindedAt(null);
        ticket.setOverdueAt(null);
        ticket.setOverdueRemindedAt(null);
    }

    private void scanSafely() {
        try {
            Date now = new Date();
            List<Ticket> candidates = TenantHelper.ignore(() -> ticketMapper.selectList(
                new LambdaQueryWrapper<Ticket>()
                    .in(Ticket::getTicketStatus, TicketStatus.OPEN, TicketStatus.PROCESSING)
                    .isNotNull(Ticket::getDueAt)
                    .and(value -> value.and(overdue -> overdue.le(Ticket::getDueAt, now)
                            .isNull(Ticket::getOverdueRemindedAt))
                        .or(upcoming -> upcoming.gt(Ticket::getDueAt, now)
                            .isNotNull(Ticket::getRemindAt).le(Ticket::getRemindAt, now)
                            .isNull(Ticket::getDueSoonRemindedAt)))
                    .orderByAsc(Ticket::getDueAt)
                    .last("LIMIT 200")));
            candidates.forEach(ticket -> TenantHelper.dynamic(ticket.getTenantId(), () -> process(ticket.getId())));
        } catch (Exception exception) {
            log.error("扫描工单办结时限提醒失败", exception);
        }
    }

    private void process(Long ticketId) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDueAt() == null
            || !List.of(TicketStatus.OPEN, TicketStatus.PROCESSING).contains(ticket.getTicketStatus())) return;
        Date now = new Date();
        if (!ticket.getDueAt().after(now) && ticket.getOverdueRemindedAt() == null) {
            claimAndNotify(ticket, now, true);
        } else if (ticket.getRemindAt() != null && !ticket.getRemindAt().after(now)
            && ticket.getDueSoonRemindedAt() == null) {
            claimAndNotify(ticket, now, false);
        }
    }

    private void claimAndNotify(Ticket ticket, Date now, boolean overdue) {
        LambdaUpdateWrapper<Ticket> update = new LambdaUpdateWrapper<Ticket>()
            .eq(Ticket::getId, ticket.getId())
            .in(Ticket::getTicketStatus, TicketStatus.OPEN, TicketStatus.PROCESSING);
        if (overdue) {
            update.isNull(Ticket::getOverdueRemindedAt)
                .set(Ticket::getOverdueRemindedAt, now)
                .set(Ticket::getOverdueAt, ticket.getOverdueAt() == null ? now : ticket.getOverdueAt())
                .set(Ticket::getDeadlineStatus, TicketDeadlineStatus.OVERDUE);
        } else {
            update.isNull(Ticket::getDueSoonRemindedAt)
                .set(Ticket::getDueSoonRemindedAt, now)
                .set(Ticket::getDeadlineStatus, TicketDeadlineStatus.DUE_SOON);
        }
        if (ticketMapper.update(null, update) != 1) return;
        try {
            if (ticket.getCreateBy() == null) {
                log.warn("工单时限提醒无接收人，ticketId={}，ticketNo={}", ticket.getId(), ticket.getTicketNo());
                return;
            }
            SseMessageDto message = new SseMessageDto();
            message.setUserIds(List.of(ticket.getCreateBy()));
            String content = overdue
                ? "工单【" + ticket.getTicketNo() + "】已超过办结时限，请尽快处理。"
                : "工单【" + ticket.getTicketNo() + "】即将于 " + format(ticket.getDueAt()) + " 到期，请及时处理。";
            message.setMessage(JsonUtils.toJsonString(Map.of(
                "type", "TICKET_DEADLINE_REMINDER",
                "eventId", "ticket-deadline:" + ticket.getId() + ":" + (overdue ? "overdue:" : "due-soon:") + now.getTime(),
                "ticketId", ticket.getId().toString(),
                "ticketNo", ticket.getTicketNo(),
                "message", content,
                "overdue", overdue
            )));
            SseMessageUtils.publishMessage(message);
        } catch (Exception exception) {
            releaseReminder(ticket.getId(), overdue);
            log.warn("发送工单时限提醒失败，ticketId={}，overdue={}", ticket.getId(), overdue, exception);
        }
    }

    private void releaseReminder(Long ticketId, boolean overdue) {
        LambdaUpdateWrapper<Ticket> update = new LambdaUpdateWrapper<Ticket>().eq(Ticket::getId, ticketId);
        if (overdue) update.set(Ticket::getOverdueRemindedAt, null);
        else update.set(Ticket::getDueSoonRemindedAt, null).set(Ticket::getDeadlineStatus, TicketDeadlineStatus.NORMAL);
        ticketMapper.update(null, update);
    }

    private String format(Date value) {
        LocalDateTime time = value.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        return TIME_FORMAT.format(time);
    }
}
