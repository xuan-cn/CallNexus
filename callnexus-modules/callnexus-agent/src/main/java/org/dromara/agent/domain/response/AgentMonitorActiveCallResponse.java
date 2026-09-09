package org.dromara.agent.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentMonitorActiveCallResponse {
    private Long agentId;
    private String businessCallId;
    private String direction;
    private String peerNumber;
    private String callState;
    private LocalDateTime startedAt;
}
