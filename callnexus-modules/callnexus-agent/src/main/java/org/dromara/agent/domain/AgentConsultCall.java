package org.dromara.agent.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AgentConsultCall implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String originalCallId;
    private String businessCallId;
    private String consultCallId;
    private String agentChannelId;
    private String customerCallId;
    private String sourceAgentCallId;
    private String targetAgentCallId;
    private String tenantId;
    private Long nodeId;
    private String customerLegUuid;
    private String sourceAgentLegUuid;
    private String consultLegUuid;
    private AgentConsultCallStatus status;
    private Long agentId;
    private String agentExtension;
    private Long targetAgentId;
    private String targetExtension;
    private String phoneMode;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime consultBridgedAt;
    private LocalDateTime completedAt;
}
