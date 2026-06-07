package org.dromara.customer.ticket.domain.request;

import lombok.Data;
import org.dromara.customer.ticket.domain.TicketStatus;

@Data
public class TicketPageQuery {
    private String ticketNo;
    private String callerNumber;
    private TicketStatus ticketStatus;
}
