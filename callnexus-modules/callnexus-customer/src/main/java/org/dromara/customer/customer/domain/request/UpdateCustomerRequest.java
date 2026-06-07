package org.dromara.customer.customer.domain.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class UpdateCustomerRequest {
    @Size(max = 64)
    private String customerName;
    private Long templateId;
    private Map<String, Object> formData = new HashMap<>();
}
