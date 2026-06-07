package org.dromara.call.service;

import org.dromara.call.domain.EslEndpoint;

public interface TelephonyCommandGateway {
    void originate(EslEndpoint endpoint, String callId, String agentExtension, String destination);
    void hangup(EslEndpoint endpoint, String callId);
    boolean callExists(EslEndpoint endpoint, String callId);
}
