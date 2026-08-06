package org.dromara.call.domain.response;

import lombok.Data;

@Data
public class CallRecordingResponse {
    private Long callSessionId;
    private String businessCallId;
    private String status;
    private Long mediaId;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private Long durationMs;
    private String playbackUrl;
}
