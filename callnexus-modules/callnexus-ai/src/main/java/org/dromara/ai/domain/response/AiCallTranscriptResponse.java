package org.dromara.ai.domain.response;

import lombok.Data;

import java.util.Date;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AiCallTranscriptResponse {
    private Long id;
    private Long callSessionId;
    private String businessCallId;
    private Long providerId;
    private String providerType;
    private Long inputMediaId;
    private Long recordingOssId;
    private String status;
    private String fullText;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Date createTime;
    private List<AiCallTranscriptSegmentResponse> segments;
}
