package org.dromara.resource.outboundauth.domain;

import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;

public record OutboundAuthorizationResult(
    boolean allowed,
    String rejectCode,
    String rejectMessage,
    String normalizedCallee,
    boolean external,
    PhoneNumberOutboundRouteResponse outboundRoute
) {

    public static OutboundAuthorizationResult allowInternal(String normalizedCallee) {
        return new OutboundAuthorizationResult(true, null, null, normalizedCallee, false, null);
    }

    public static OutboundAuthorizationResult allowExternal(String normalizedCallee, PhoneNumberOutboundRouteResponse outboundRoute) {
        return new OutboundAuthorizationResult(true, null, null, normalizedCallee, true, outboundRoute);
    }

    public static OutboundAuthorizationResult reject(String rejectCode, String rejectMessage, String normalizedCallee) {
        return new OutboundAuthorizationResult(false, rejectCode, rejectMessage, normalizedCallee, false, null);
    }
}
