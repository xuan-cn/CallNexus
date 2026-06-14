package org.dromara.agent.domain.response;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentRealtimeTargetResponse implements Serializable {
    private String tenantId;
    private Long agentId;
    private Long userId;
    private Long nodeId;
    private String extension;
    private String sipDomain;
}
