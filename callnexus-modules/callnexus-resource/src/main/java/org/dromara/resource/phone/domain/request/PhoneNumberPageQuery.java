package org.dromara.resource.phone.domain.request;

import lombok.Data;

@Data
public class PhoneNumberPageQuery {
    private Long nodeId;
    private Long gatewayId;
    private String number;
    private String numberName;
    private String numberType;
    private String routeType;
    private Boolean enabled;
}
