package org.dromara.customer.ticket.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateTicketUrgencyRequest {
    @NotNull
    private Long urgencyLevelId;
}
