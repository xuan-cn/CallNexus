package org.dromara.resource.phone.domain.response;

import lombok.Data;

@Data
public class PhoneNumberDialplanRouteResponse {
    private Long id;
    private String number;
    private String routeType;
    private String routeTarget;
    private String sipDomain;
}
