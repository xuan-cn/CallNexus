package org.dromara.resource.phone.domain.response;

import lombok.Data;

@Data
public class PhoneNumberOutboundRouteResponse {
    private Long numberId;
    private String number;
    private Long gatewayId;
    private String gatewayCode;
    private String gatewayName;
}
