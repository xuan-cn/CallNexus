package org.dromara.agent.runtime;

import org.dromara.agent.domain.AgentPresenceStatus;

public record QueueAgentRuntimeConfig(
    Long agentId,
    String agentName,
    String extension,
    String sipDomain,
    Integer level,
    Integer position,
    Integer ringTimeoutSeconds,
    Integer maxNoAnswer,
    Integer wrapUpSeconds,
    AgentPresenceStatus presenceStatus
) {
}
