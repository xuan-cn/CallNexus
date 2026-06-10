package org.dromara.call.service;

import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;

public interface TelephonyCommandGateway {
    void originate(EslEndpoint endpoint, String callId, String agentExtension, String destination, OutboundRoute outboundRoute);
    void hangup(EslEndpoint endpoint, String callId);
    boolean callExists(EslEndpoint endpoint, String callId);
}
