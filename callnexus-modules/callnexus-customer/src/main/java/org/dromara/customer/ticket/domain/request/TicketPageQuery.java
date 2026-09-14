package org.dromara.customer.ticket.domain.request;

import lombok.Data;
import org.dromara.customer.ticket.domain.TicketStatus;
import org.dromara.customer.form.domain.request.DynamicFieldFilter;

import java.util.List;

@Data
public class TicketPageQuery {
    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private String ticketNo;
    private String callerNumber;
    private TicketStatus ticketStatus;
    private Long templateId;
    private List<DynamicFieldFilter> dynamicFilters;
}
