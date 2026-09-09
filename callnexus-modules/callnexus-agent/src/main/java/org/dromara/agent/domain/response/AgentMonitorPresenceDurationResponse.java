package org.dromara.agent.domain.response;

import lombok.Data;

@Data
public class AgentMonitorPresenceDurationResponse {
    private Long agentId;
    private Long onlineSeconds;
    private Long idleSeconds;
    private Long notReadySeconds;
    private Long busySeconds;
    private Long afterCallSeconds;
}
