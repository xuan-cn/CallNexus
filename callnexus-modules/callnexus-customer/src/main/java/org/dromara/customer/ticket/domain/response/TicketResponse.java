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
    private String workflowCode;
    private String processStatus;
    private Long flowInstanceId;
    private String currentNodeCode;
    private String currentNodeName;
    private LocalDateTime submittedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private LocalDateTime createTime;
    private Map<String, Object> formData;
}
