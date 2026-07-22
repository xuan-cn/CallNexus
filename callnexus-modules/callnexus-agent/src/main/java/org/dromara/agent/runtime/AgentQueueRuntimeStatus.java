package org.dromara.agent.runtime;

import org.dromara.agent.domain.AgentPresenceStatus;

public record AgentQueueRuntimeStatus(
    Long nodeId,
    String extension,
    String authUsername,
    String sipDomain,
    AgentPresenceStatus presenceStatus
) {
}
