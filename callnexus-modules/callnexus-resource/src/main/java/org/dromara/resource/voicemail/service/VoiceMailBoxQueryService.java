package org.dromara.resource.voicemail.service;

import org.dromara.resource.voicemail.domain.response.VoiceMailDialplanResponse;

public interface VoiceMailBoxQueryService {
    boolean isAvailable(String tenantId, Long boxId, Long nodeId);

    VoiceMailDialplanResponse findAvailableBox(String tenantId, Long boxId, Long nodeId);
}
