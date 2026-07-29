package org.dromara.call.service;

import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;
import org.dromara.call.domain.CallOriginateContext;

import java.util.Set;

public interface TelephonyCommandGateway {
    void originate(EslEndpoint endpoint, String callId, String agentExtension, String destination,
                   OutboundRoute outboundRoute, CallOriginateContext context);
    void hangup(EslEndpoint endpoint, String callId);
    void hold(EslEndpoint endpoint, String callId);
    void unhold(EslEndpoint endpoint, String callId);
    void mute(EslEndpoint endpoint, String callId);
    void unmute(EslEndpoint endpoint, String callId);
    void sendDtmf(EslEndpoint endpoint, String callId, String digits);
    void broadcastPlayback(EslEndpoint endpoint, String callId, String mediaPath, String leg);
    void broadcastSayNumber(EslEndpoint endpoint, String callId, String language, String number, String leg);
    void park(EslEndpoint endpoint, String callId);
    void recoverMedia(EslEndpoint endpoint, String callId);
    void setCallVariable(EslEndpoint endpoint, String callId, String name, String value);
    void bridgeCalls(EslEndpoint endpoint, String leftCallId, String rightCallId);
    boolean callsAreBridged(EslEndpoint endpoint, String leftCallId, String rightCallId);
    void blindTransfer(EslEndpoint endpoint, String callId, String targetExtension);
    void originateConsultation(EslEndpoint endpoint, String businessCallId, String consultCallId, String agentExtension,
                               String targetExtension, String customerLegUuid, String sourceAgentLegUuid);
    boolean callExists(EslEndpoint endpoint, String callId);
    Set<String> listRegisteredExtensions(EslEndpoint endpoint);
    void originateMonitor(EslEndpoint endpoint, String businessCallId, String monitorLegUuid,
                          String supervisorExtension, String targetAgentLegUuid, String targetExtension);
    void originateWhisper(EslEndpoint endpoint, String businessCallId, String whisperLegUuid,
                          String supervisorExtension, String targetAgentLegUuid, String targetExtension);
    void originateBarge(EslEndpoint endpoint, String businessCallId, String bargeLegUuid,
                        String supervisorExtension, String targetAgentLegUuid, String targetExtension);
    void originatePickup(EslEndpoint endpoint, String businessCallId, String pickupLegUuid,
                         String supervisorExtension, String interceptSourceLegUuid,
                         String targetRingingLegUuid, String callerNumber);
    void originateDispatchParticipant(EslEndpoint endpoint, String businessCallId, String participantLegUuid,
                                      String conferenceName, String extension, String callerIdNumber,
                                      String purpose, Long dispatchTaskId, Long dispatchTargetId);
    void promoteBridgeToConference(EslEndpoint endpoint, String anchorLegUuid, String conferenceName);
    void originateConferenceParticipant(EslEndpoint endpoint, String businessCallId, String participantLegUuid,
                                        String conferenceName, String extension, String callerIdNumber);
    String conferenceMemberList(EslEndpoint endpoint, String conferenceName);
    void muteConferenceMember(EslEndpoint endpoint, String conferenceName, String conferenceMemberId, boolean muted);
    void removeConferenceMember(EslEndpoint endpoint, String conferenceName, String conferenceMemberId);
    void terminateConference(EslEndpoint endpoint, String conferenceName);
    void originateDispatchPlayback(EslEndpoint endpoint, String businessCallId, String targetLegUuid,
                                   String extension, String callerIdNumber, String mediaPath,
                                   Long dispatchTaskId, Long dispatchTargetId);
}
