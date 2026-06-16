package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

@Data
public class VoiceMailMessageResponse {
    private Long id;
    private Long voicemailBoxId;
    private String businessCallId;
    private Long callSessionId;
    private Long nodeId;
    private String callerNumber;
    private String calledNumber;
    private Long customerId;
    private Long ticketId;
    private Long recordingOssId;
    private Long recordingMediaId;
    private String recordingFileName;
    private Long durationMs;
    private String status;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String handleRemark;
    private String playbackUrl;
    private Date createTime;
    private Integer version;
}
