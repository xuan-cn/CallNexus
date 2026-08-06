package org.dromara.call.service;

import org.dromara.call.domain.response.CallRecordingResponse;
import org.springframework.web.multipart.MultipartFile;

public interface CallRecordingApplicationService {
    void upload(String tenantId, String businessCallId, MultipartFile file);

    CallRecordingResponse getByBusinessCallId(String businessCallId);
}
