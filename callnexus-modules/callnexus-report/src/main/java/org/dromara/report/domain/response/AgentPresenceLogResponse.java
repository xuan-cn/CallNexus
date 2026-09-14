package org.dromara.report.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentPresenceLogResponse {
    private Long id;
    private String previousStatus;
    private String status;
    private String source;
    private String businessCallId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationSeconds;
}
