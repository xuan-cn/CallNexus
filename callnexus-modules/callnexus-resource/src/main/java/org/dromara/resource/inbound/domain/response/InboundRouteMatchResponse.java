package org.dromara.resource.inbound.domain.response;

import lombok.Data;

@Data
public class InboundRouteMatchResponse {
    private Boolean matched;
    private String matchedType;
    private String matchedMessage;
    private Long entryId;
    private String entryName;
    private String entryType;
    private String didNumber;
    private String portCode;
    private String accountCode;
    private String matchValue;
    private Long gatewayId;
    private String gatewayName;
    private String gatewayCode;
    private Long nodeId;
    private String routeTargetType;
    private String routeTargetId;
    private String routeTargetName;
    private Integer priority;
}
