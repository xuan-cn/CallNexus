package org.dromara.agent.domain.response;

import lombok.Data;
import org.dromara.agent.domain.AgentCallOperation;
import org.dromara.agent.domain.AgentCallPhase;
import org.dromara.agent.domain.AgentPresenceStatus;

import java.time.LocalDateTime;

@Data
public class CurrentAgentResponse {
    private boolean configured;
    private Long agentId;
    private String agentCode;
    private String agentName;
    private Long userId;
    private Long callerNumberId;
    private Long sipAccountId;
    private Long nodeId;
    private String extension;
    private String authUsername;
    private String sipDisplayName;
    private String sipDomain;
    private String wssUrl;
    private String activeCallId;
    private String activeCallNumber;
    private String activeAgentLegUuid;
    private AgentCallPhase activeCallPhase;
    private AgentCallOperation activeCallOperation;
    private Long activeCallStateVersion;
    private AgentPresenceStatus status;
    private Long afterCallRemainingSeconds;
    private LocalDateTime signedInAt;
    private LocalDateTime updatedAt;
}
