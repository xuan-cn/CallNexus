package org.dromara.call.domain;

import lombok.Data;

@Data
public class OutboundRoute {
    private boolean external;
    private String gatewayCode;
    private String callerIdNumber;

    public static OutboundRoute internal() {
        OutboundRoute route = new OutboundRoute();
        route.setExternal(false);
        return route;
    }

    public static OutboundRoute external(String gatewayCode, String callerIdNumber) {
        OutboundRoute route = new OutboundRoute();
        route.setExternal(true);
        route.setGatewayCode(gatewayCode);
        route.setCallerIdNumber(callerIdNumber);
        return route;
    }
}
