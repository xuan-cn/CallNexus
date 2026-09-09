package org.dromara.agent.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentMonitorCallResponse {
    private Long legId;
    private Long sessionId;
    private String businessCallId;
    private String direction;
    private String customerNumber;
    private String legState;
    private LocalDateTime ringingAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Long talkSeconds;
    private String hangupCause;
}
