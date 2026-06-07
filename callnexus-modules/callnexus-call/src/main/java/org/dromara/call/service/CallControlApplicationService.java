package org.dromara.call.service;

import org.dromara.call.domain.response.CallControlResponse;

public interface CallControlApplicationService {
    CallControlResponse originate(String destination);
    void hangup(String callId);
}
