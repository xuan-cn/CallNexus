package org.dromara.customer.ticket.domain.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class CreateTicketRequest {
    private Long customerId;
    @Size(max = 32)
    private String callerNumber;
    @Size(max = 64)
    private String sourceCallId;
    private Long templateId;
    private Map<String, Object> formData = new HashMap<>();
}
