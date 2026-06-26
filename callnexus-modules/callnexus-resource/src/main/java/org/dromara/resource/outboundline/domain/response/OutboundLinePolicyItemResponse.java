package org.dromara.resource.outboundline.domain.response;

import lombok.Data;

@Data
public class OutboundLinePolicyItemResponse {
    private Long id;
    private Long policyId;
    private Long phoneNumberId;
    private String phoneNumber;
    private String phoneNumberName;
    private Integer weight;
    private Integer sortOrder;
    private Boolean enabled;
    private Integer version;
}
