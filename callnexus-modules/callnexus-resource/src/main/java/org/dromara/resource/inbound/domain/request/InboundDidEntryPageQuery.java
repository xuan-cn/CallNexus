package org.dromara.resource.inbound.domain.request;

import lombok.Data;

@Data
public class InboundDidEntryPageQuery {
    private Long nodeId;
    private Long gatewayId;
    private Long phoneNumberId;
    private String entryName;
    private String entryType;
    private String didNumber;
    private String routeTargetType;
    private Boolean enabled;
}
