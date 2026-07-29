package org.dromara.customer.customer.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomerPhoneRequest {
    @NotBlank
    @Size(max = 32)
    private String phoneNumber;

    @Pattern(regexp = "MOBILE|HOME|WORK|OTHER")
    private String phoneType;

    @Size(max = 32)
    private String phoneLabel;

    private Boolean primaryFlag = false;
    private Boolean enabled = true;
    private Integer sortOrder = 0;
}
