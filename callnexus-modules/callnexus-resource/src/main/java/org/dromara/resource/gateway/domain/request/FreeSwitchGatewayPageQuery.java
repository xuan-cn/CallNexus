package org.dromara.resource.gateway.domain.request;

import lombok.Data;

@Data
public class FreeSwitchGatewayPageQuery {
    private Long nodeId;
    private String gatewayCode;
    private String gatewayName;
    private String direction;
    private Boolean enabled;
}
