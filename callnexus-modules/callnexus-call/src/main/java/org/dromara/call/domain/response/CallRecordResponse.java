package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CallRecordResponse {
    private Long id;
    private String businessCallId;
    private Long nodeId;
    private String channelUuid;
    private String callUuid;
    private String direction;
    private String callerNumber;
    private String calledNumber;
    private Long agentId;
    private String agentExtension;
    private Long customerId;
    private Long ticketId;
    private String callStatus;
    private LocalDateTime startedAt;
    private LocalDateTime ringingAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private String hangupCause;
    private Long recordingOssId;
    private Long recordingMediaId;
    private String recordingFileName;
    private String recordingStatus;
    private String recordingUrl;
    private List<CallLegResponse> legs;
    private List<CallEventResponse> events;
}
