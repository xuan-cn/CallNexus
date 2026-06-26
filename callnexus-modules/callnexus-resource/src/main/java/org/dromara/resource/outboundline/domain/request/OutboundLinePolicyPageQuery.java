package org.dromara.resource.outboundline.domain.request;

import lombok.Data;

@Data
public class OutboundLinePolicyPageQuery {
    private Long nodeId;
    private String policyCode;
    private String policyName;
    private String policyType;
    private Boolean defaultPolicy;
    private Boolean enabled;
}
