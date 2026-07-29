package org.dromara.call.service;

import org.dromara.call.domain.response.CallConferenceResponse;

public interface CallConferenceApplicationService {
    CallConferenceResponse create(String callId);

    CallConferenceResponse get(String callId);

    CallConferenceResponse invite(String callId, String targetExtension);

    CallConferenceResponse muteMember(String callId, Long memberRecordId, boolean muted);

    CallConferenceResponse removeMember(String callId, Long memberRecordId);

    void leave(String callId);

    void end(String callId);
}
