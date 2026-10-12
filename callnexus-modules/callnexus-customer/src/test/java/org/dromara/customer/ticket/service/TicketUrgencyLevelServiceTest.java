package org.dromara.customer.ticket.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketUrgencyLevel;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.customer.ticket.mapper.TicketUrgencyLevelMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class TicketUrgencyLevelServiceTest {

    @Test
    void appliesSelectedLevelSnapshotToTicket() {
        TicketUrgencyLevelMapper mapper = mock(TicketUrgencyLevelMapper.class);
        TicketUrgencyLevel level = level(9L, "URGENT", "紧急", 60, "#E6A23C", true);
        when(mapper.selectById(9L)).thenReturn(level);
        TicketUrgencyLevelService service = new TicketUrgencyLevelService(mapper, mock(TicketMapper.class));
        Ticket ticket = new Ticket();

        service.applyToTicket(ticket, 9L);

        assertEquals(9L, ticket.getUrgencyLevelId());
        assertEquals("URGENT", ticket.getUrgencyCode());
        assertEquals("紧急", ticket.getUrgencyName());
        assertEquals(60, ticket.getUrgencyWeight());
        assertEquals("#E6A23C", ticket.getUrgencyColor());
    }

    @Test
    void appliesConfiguredDefaultWhenNoLevelWasSelected() {
        TicketUrgencyLevelMapper mapper = mock(TicketUrgencyLevelMapper.class);
        TicketUrgencyLevel level = level(1L, "NORMAL", "普通", 10, "#909399", true);
        when(mapper.exists(any())).thenReturn(true);
        when(mapper.selectOne(any())).thenReturn(level);
        TicketUrgencyLevelService service = new TicketUrgencyLevelService(mapper, mock(TicketMapper.class));
        Ticket ticket = new Ticket();

        service.applyToTicket(ticket, null);

        assertEquals(1L, ticket.getUrgencyLevelId());
        assertEquals("NORMAL", ticket.getUrgencyCode());
    }

    @Test
    void rejectsDisabledLevel() {
        TicketUrgencyLevelMapper mapper = mock(TicketUrgencyLevelMapper.class);
        when(mapper.selectById(8L)).thenReturn(level(8L, "OLD", "已停用", 20, "#909399", false));
        TicketUrgencyLevelService service = new TicketUrgencyLevelService(mapper, mock(TicketMapper.class));

        assertThrows(ServiceException.class, () -> service.applyToTicket(new Ticket(), 8L));
    }

    private TicketUrgencyLevel level(Long id, String code, String name, int weight, String color, boolean enabled) {
        TicketUrgencyLevel level = new TicketUrgencyLevel();
        level.setId(id);
        level.setLevelCode(code);
        level.setLevelName(name);
        level.setPriorityWeight(weight);
        level.setTagColor(color);
        level.setEnabled(enabled);
        return level;
    }
}
