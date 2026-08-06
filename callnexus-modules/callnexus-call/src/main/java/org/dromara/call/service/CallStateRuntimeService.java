package org.dromara.call.service;

import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.call.domain.TelephonyEvent;

public interface CallStateRuntimeService {

    void handleEvent(TelephonyEvent event);

    String resolveBusinessCallId(TelephonyEvent event);

    String resolveBusinessCallId(TelephonyEvent event, AgentActiveCall existing);

    /**
     * Resolve the canonical business call id from persisted leg ownership and related leg UUIDs.
     */
    String resolveCanonicalBusinessCallId(TelephonyEvent event);
}
