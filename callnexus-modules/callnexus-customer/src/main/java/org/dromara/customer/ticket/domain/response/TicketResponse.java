package org.dromara.customer.ticket.domain.response;

import lombok.Data;
import org.dromara.customer.ticket.domain.TicketStatus;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TicketResponse {
    private Long id;
    private String ticketNo;
    private TicketStatus ticketStatus;
    private Long customerId;
    private String callerNumber;
    private String sourceCallId;
    private Long templateId;
    private LocalDateTime createTime;
    private Map<String, Object> formData;
}
