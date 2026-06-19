package org.dromara.call.service;

import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.CallOriginateContext;

public interface CallControlApplicationService {
    CallControlResponse originate(String destination);
    CallControlResponse originate(String destination, CallOriginateContext context);
    void hangup(String callId);
    void hold(String callId);
    void unhold(String callId);
    void blindTransfer(String callId, String targetExtension);
    CallControlResponse startConsultTransfer(String callId, String targetExtension);
    void cancelConsultTransfer(String callId);
    void completeConsultTransfer(String callId);
}
