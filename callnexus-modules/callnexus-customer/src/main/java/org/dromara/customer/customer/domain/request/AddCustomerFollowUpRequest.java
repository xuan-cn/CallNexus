package org.dromara.customer.customer.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddCustomerFollowUpRequest {
    @NotBlank
    @Size(max = 2000)
    private String content;
}
