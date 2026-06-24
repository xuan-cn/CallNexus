package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentCallSessionResponse {
    private Long id;
    private String businessCallId;
    private Long nodeId;
    private Long agentId;
    private String agentExtension;
    private String agentLegUuid;
    private String role;
    private String sessionState;
    private Boolean visible;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
}
