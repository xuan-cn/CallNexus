package org.dromara.agent.domain.response;

import lombok.Data;
import org.dromara.agent.domain.AgentPresenceStatus;

import java.time.LocalDateTime;

@Data
public class CurrentAgentResponse {
    private boolean configured;
    private Long agentId;
    private String agentCode;
    private String agentName;
    private Long userId;
    private Long sipAccountId;
    private Long nodeId;
    private String extension;
    private String sipDisplayName;
    private String sipDomain;
    private String wssUrl;
    private String activeCallId;
    private String activeCallNumber;
    private AgentPresenceStatus status;
    private LocalDateTime signedInAt;
    private LocalDateTime updatedAt;
}
