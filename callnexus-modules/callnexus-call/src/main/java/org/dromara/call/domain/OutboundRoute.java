package org.dromara.call.domain;

import lombok.Data;

@Data
public class OutboundRoute {
    private boolean external;
    private String gatewayCode;
    private String callerIdNumber;
    private String gatewayAccessMode;
    private String registeredIdentity;
    private String gatewaySipProfile;
    private String sipDomain;

    public static OutboundRoute internal() {
        OutboundRoute route = new OutboundRoute();
        route.setExternal(false);
        return route;
    }

    public static OutboundRoute external(String gatewayCode, String callerIdNumber) {
        return external(gatewayCode, callerIdNumber, null, null, null, null);
    }

    public static OutboundRoute external(String gatewayCode, String callerIdNumber, String gatewayAccessMode,
                                         String registeredIdentity, String gatewaySipProfile, String sipDomain) {
        OutboundRoute route = new OutboundRoute();
        route.setExternal(true);
        route.setGatewayCode(gatewayCode);
        route.setCallerIdNumber(callerIdNumber);
        route.setGatewayAccessMode(gatewayAccessMode);
        route.setRegisteredIdentity(registeredIdentity);
        route.setGatewaySipProfile(gatewaySipProfile);
        route.setSipDomain(sipDomain);
        return route;
    }
}
