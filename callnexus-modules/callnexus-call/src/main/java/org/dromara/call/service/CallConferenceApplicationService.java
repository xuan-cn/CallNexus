package org.dromara.call.service;

import org.dromara.call.domain.response.CallConferenceResponse;

import java.util.List;

public interface CallConferenceApplicationService {
    CallConferenceResponse create(String callId);
    CallConferenceResponse create(Long agentId, String callId);

    CallConferenceResponse createStandalone(Long agentId, String displayName, List<String> targetExtensions);

    CallConferenceResponse getById(Long agentId, Long conferenceId);

    CallConferenceResponse inviteById(Long agentId, Long conferenceId, List<String> targetExtensions);

    CallConferenceResponse muteMemberById(Long agentId, Long conferenceId, Long memberRecordId, boolean muted);

    CallConferenceResponse removeMemberById(Long agentId, Long conferenceId, Long memberRecordId);

    void leaveById(Long agentId, Long conferenceId);

    void endById(Long agentId, Long conferenceId);

    CallConferenceResponse promoteConsult(String callId);
    CallConferenceResponse promoteConsult(Long agentId, String callId);

    CallConferenceResponse get(String callId);
    CallConferenceResponse get(Long agentId, String callId);

    CallConferenceResponse invite(String callId, String targetExtension);
    CallConferenceResponse invite(Long agentId, String callId, String targetExtension);

    CallConferenceResponse muteMember(String callId, Long memberRecordId, boolean muted);
    CallConferenceResponse muteMember(Long agentId, String callId, Long memberRecordId, boolean muted);

    CallConferenceResponse removeMember(String callId, Long memberRecordId);
    CallConferenceResponse removeMember(Long agentId, String callId, Long memberRecordId);

    void leave(String callId);
    void leave(Long agentId, String callId);

    void end(String callId);
    void end(Long agentId, String callId);

    boolean endIfActiveOwner(Long agentId, String callId);

    void handleMemberHangup(Long nodeId, String legUuid);
}
