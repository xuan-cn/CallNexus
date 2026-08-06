package org.dromara.call.service;

import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.CallOriginateContext;

public interface CallControlApplicationService {
    CallControlResponse originate(String destination);
    CallControlResponse originate(String destination, CallOriginateContext context);
    CallControlResponse originate(Long agentId, String destination, CallOriginateContext context);
    void hangup(String callId);
    void hangup(Long agentId, String callId);
    void hold(String callId);
    void hold(Long agentId, String callId);
    void unhold(String callId);
    void unhold(Long agentId, String callId);
    void mute(String callId);
    void mute(Long agentId, String callId);
    void unmute(String callId);
    void unmute(Long agentId, String callId);
    void sendDtmf(String callId, String digits);
    void sendDtmf(Long agentId, String callId, String digits);
    void saveNote(String callId, String content);
    void blindTransfer(String callId, String targetExtension);
    void blindTransfer(Long agentId, String callId, String targetExtension);
    void transferToIvr(String callId, Long flowId);
    void transferToIvr(Long agentId, String callId, Long flowId);
    CallControlResponse startConsultTransfer(String callId, String targetExtension, String phoneMode);
    CallControlResponse startConsultTransfer(Long agentId, String callId, String targetExtension, String phoneMode);
    void cancelConsultTransfer(String callId);
    void cancelConsultTransfer(Long agentId, String callId);
    void completeConsultTransfer(String callId);
    void completeConsultTransfer(Long agentId, String callId);
}
