package org.dromara.customer.customer.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ClaimCustomerRequest {
    @NotBlank
    @Size(max = 64)
    private String businessCallId;
}
