package org.dromara.resource.sip.security.domain.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SipSecurityPolicySaveRequest {
    @NotNull
    private Long nodeId;
    @NotNull
    private Boolean enabled;
    @NotNull @Min(10) @Max(86400)
    private Integer pairWindowSeconds;
    @NotNull @Min(2) @Max(1000)
    private Integer pairFailureThreshold;
    @NotNull @Min(10) @Max(86400)
    private Integer accountWindowSeconds;
    @NotNull @Min(2) @Max(1000)
    private Integer accountFailureThreshold;
    @NotNull @Min(10) @Max(86400)
    private Integer ipWindowSeconds;
    @NotNull @Min(2) @Max(10000)
    private Integer ipFailureThreshold;
    @NotNull @Min(60) @Max(2592000)
    private Integer firstBlockSeconds;
    @NotNull @Min(60) @Max(2592000)
    private Integer secondBlockSeconds;
    @NotNull @Min(60) @Max(2592000)
    private Integer repeatedBlockSeconds;
    @NotNull
    private Boolean alertEnabled;
    private Integer version;
}
