package org.dromara.agent.runtime;

import java.util.List;

public record QueueNodeRuntimeConfig(
    Long nodeId,
    String queueCode,
    String strategy,
    String waitMediaPath,
    Integer maxWaitSeconds,
    List<QueueAgentRuntimeConfig> agents
) {
}
