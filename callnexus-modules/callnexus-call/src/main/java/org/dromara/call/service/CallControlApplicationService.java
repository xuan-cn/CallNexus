package org.dromara.call.service;

import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.CallOriginateContext;

public interface CallControlApplicationService {
    CallControlResponse originate(String destination);
    CallControlResponse originate(String destination, CallOriginateContext context);
    void hangup(String callId);
}
