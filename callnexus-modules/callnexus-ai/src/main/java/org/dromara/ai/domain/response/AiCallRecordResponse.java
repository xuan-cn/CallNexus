package org.dromara.ai.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiCallRecordResponse {

    private Long transcriptId;
    private Long callSessionId;
    private String businessCallId;
    private Long nodeId;
    private String direction;
    private String callerNumber;
    private String calledNumber;
    private Long agentId;
    private String agentExtension;
    private Long ownerAgentId;
    private String ownerAgentExtension;
    private Long handlingQueueId;
    private String handlingQueueName;
    private String callStatus;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Long durationSeconds;
    private Long billableSeconds;
    private String hangupCause;
    private Long recordingOssId;
    private Long recordingMediaId;
    private String recordingFileName;
    private String recordingStatus;
    private String recordingUrl;
    private String transcriptStatus;
    private String transcriptFailureReason;
    private LocalDateTime transcriptStartedAt;
    private LocalDateTime transcriptFinishedAt;
    private Long segmentCount;
    private Long customerSegmentCount;
    private Long aiSegmentCount;
    private Long agentSegmentCount;
}
