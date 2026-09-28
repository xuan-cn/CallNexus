package org.dromara.customer.ticket.service;

import org.dromara.customer.form.domain.FormTemplate;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketDeadlineStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("dev")
class TicketDeadlineServiceTest {
    private final TicketDeadlineService service = new TicketDeadlineService(null, null);

    @Test
    void initializesDeadlineAndReminderFromTemplate() {
        FormTemplate template = new FormTemplate();
        template.setDeadlineEnabled(true);
        template.setResolutionLimitMinutes(120);
        template.setRemindBeforeMinutes(30);
        Ticket ticket = new Ticket();
        Date createdAt = new Date(1_000_000L);

        service.initialize(ticket, template, createdAt);

        assertEquals(new Date(1_000_000L + 120 * 60_000L), ticket.getDueAt());
        assertEquals(new Date(1_000_000L + 90 * 60_000L), ticket.getRemindAt());
        assertEquals(TicketDeadlineStatus.NORMAL, ticket.getDeadlineStatus());
    }

    @Test
    void leavesDeadlineEmptyWhenTemplateDoesNotEnableIt() {
        FormTemplate template = new FormTemplate();
        template.setDeadlineEnabled(false);
        Ticket ticket = new Ticket();

        service.initialize(ticket, template, new Date());

        assertNull(ticket.getDueAt());
        assertNull(ticket.getDeadlineStatus());
    }

    @Test
    void distinguishesOnTimeAndOverdueCompletion() {
        Ticket onTime = ticketDueAt(2_000_000L);
        service.complete(onTime, new Date(1_999_999L));
        assertEquals(TicketDeadlineStatus.COMPLETED, onTime.getDeadlineStatus());

        Ticket overdue = ticketDueAt(2_000_000L);
        service.complete(overdue, new Date(2_000_001L));
        assertEquals(TicketDeadlineStatus.COMPLETED_OVERDUE, overdue.getDeadlineStatus());
        assertEquals(new Date(2_000_001L), overdue.getOverdueAt());
    }

    @Test
    void configuresAndClearsSingleTicketDeadline() {
        Ticket ticket = new Ticket();
        Date now = new Date(1_000_000L);
        Date dueAt = new Date(1_000_000L + 90 * 60_000L);

        service.configure(ticket, true, dueAt, 15, now);

        assertEquals(dueAt, ticket.getDueAt());
        assertEquals(new Date(dueAt.getTime() - 15 * 60_000L), ticket.getRemindAt());
        assertEquals(90, ticket.getResolutionLimitMinutes());
        assertEquals(TicketDeadlineStatus.NORMAL, ticket.getDeadlineStatus());

        service.configure(ticket, false, null, null, now);

        assertNull(ticket.getDueAt());
        assertNull(ticket.getRemindAt());
        assertNull(ticket.getDeadlineStatus());
    }

    private Ticket ticketDueAt(long timestamp) {
        Ticket ticket = new Ticket();
        ticket.setDueAt(new Date(timestamp));
        return ticket;
    }
}
