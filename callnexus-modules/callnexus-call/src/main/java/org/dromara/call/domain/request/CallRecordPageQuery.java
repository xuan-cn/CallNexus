package org.dromara.call.domain.request;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.Set;

@Data
public class CallRecordPageQuery {
    private Long customerId;
    private Long ticketId;
    private String participantNumber;
    private String callerNumber;
    private String calledNumber;
    private String direction;
    private String callStatus;
    private String answerResult;
    private String hangupCause;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAtFrom;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAtTo;
    private Integer minRecordingDurationSeconds;
    private Integer maxRecordingDurationSeconds;
    private Boolean dataScopeRestricted;
    private Set<Long> dataScopeAgentIds;
    private Set<Long> dataScopeQueueIds;
}
