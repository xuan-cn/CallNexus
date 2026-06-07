package org.dromara.customer.customer.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class CreateCustomerRequest {
    @NotBlank
    @Size(max = 32)
    private String primaryPhone;
    @Size(max = 64)
    private String customerName;
    private Long templateId;
    @Size(max = 64)
    private String sourceCallId;
    private Map<String, Object> formData = new HashMap<>();
}
