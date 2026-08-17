package org.dromara.resource.phone.domain.response;

import lombok.Data;

@Data
public class PhoneNumberOutboundRouteResponse {
    private Long numberId;
    private String number;
    private Long gatewayId;
    private String gatewayCode;
    private String gatewayName;
    private String gatewayAccessMode;
    private String registeredIdentity;
    private String gatewaySipProfile;
    private String sipDomain;
    private Long policyId;
    private Long policyItemId;
    private String policyCode;
    private String policyName;
    private String policyType;
}
