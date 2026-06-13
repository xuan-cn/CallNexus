package org.dromara.call.service;

import org.springframework.web.multipart.MultipartFile;

public interface CallRecordingApplicationService {
    void upload(String tenantId, String businessCallId, MultipartFile file);
}
