package org.dromara.agent.runtime;

import org.dromara.agent.domain.AgentPresenceStatus;

public record AgentQueueRuntimeStatus(
    Long nodeId,
    String extension,
    String sipDomain,
    AgentPresenceStatus presenceStatus
) {
}
