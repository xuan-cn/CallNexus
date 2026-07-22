package org.dromara.agent.runtime;

import java.util.List;

public record QueueNodeRuntimeConfig(
    Long nodeId,
    String queueCode,
    String strategy,
    String waitMediaPath,
    Boolean queueAnnounceEnabled,
    Integer queueAnnounceInterval,
    String queueAnnounceMediaPath,
    String answerAction,
    String agentNoAnswerAction,
    Integer maxWaitSeconds,
    List<QueueAgentRuntimeConfig> agents
) {
}
