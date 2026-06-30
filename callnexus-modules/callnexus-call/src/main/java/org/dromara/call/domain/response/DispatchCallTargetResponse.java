package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DispatchCallTargetResponse {
    private Long id;
    private Long sipAccountId;
    private String targetExtension;
    private String targetLegUuid;
    private String targetState;
    private Boolean answered;
    private String failureReason;
    private LocalDateTime submittedAt;
    private LocalDateTime ringingAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
}
