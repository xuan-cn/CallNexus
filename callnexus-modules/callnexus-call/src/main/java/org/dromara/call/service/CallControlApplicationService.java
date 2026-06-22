package org.dromara.call.service;

import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.CallOriginateContext;

public interface CallControlApplicationService {
    CallControlResponse originate(String destination);
    CallControlResponse originate(String destination, CallOriginateContext context);
    void hangup(String callId);
    void hold(String callId);
    void unhold(String callId);
    void mute(String callId);
    void unmute(String callId);
    void sendDtmf(String callId, String digits);
    void saveNote(String callId, String content);
    void blindTransfer(String callId, String targetExtension);
    CallControlResponse startConsultTransfer(String callId, String targetExtension, String phoneMode);
    void cancelConsultTransfer(String callId);
    void completeConsultTransfer(String callId);
}
