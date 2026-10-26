package org.dromara.resource.sip.security.domain.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SipRegistrationLockRequest {
    @Size(max = 255)
    private String reason;
}
