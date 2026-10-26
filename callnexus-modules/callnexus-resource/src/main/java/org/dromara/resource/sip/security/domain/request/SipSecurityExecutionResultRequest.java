package org.dromara.resource.sip.security.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SipSecurityExecutionResultRequest {
    @NotBlank
    private String leaseToken;
    @NotNull
    private Boolean success;
    private String failureReason;
}
