package org.dromara.outbound.domain.request;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
public class OutboundAttemptPageQuery {
    private Long taskId;
    private Long agentId;
    private String phoneNumber;
    private String resultCode;
    private String suggestedResultCode;
    private String hangupCause;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAtBegin;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAtEnd;
}
