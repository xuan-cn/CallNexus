package org.dromara.call.service;

import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;
import org.dromara.call.domain.CallOriginateContext;

public interface TelephonyCommandGateway {
    void originate(EslEndpoint endpoint, String callId, String agentExtension, String destination, OutboundRoute outboundRoute,
                   CallOriginateContext context);
    void hangup(EslEndpoint endpoint, String callId);
    boolean callExists(EslEndpoint endpoint, String callId);
}
