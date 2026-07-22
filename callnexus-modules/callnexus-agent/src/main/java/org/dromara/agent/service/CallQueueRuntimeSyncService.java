package org.dromara.agent.service;

import org.dromara.agent.runtime.AgentQueueRuntimeStatus;
import org.dromara.agent.runtime.QueueNodeRuntimeConfig;
import org.dromara.agent.runtime.QueueRuntimeSyncResult;

import java.util.List;

public interface CallQueueRuntimeSyncService {
    QueueRuntimeSyncResult syncQueue(List<QueueNodeRuntimeConfig> nodes);

    QueueRuntimeSyncResult removeQueue(List<Long> nodeIds, String queueCode);

    void syncQueueAgentStatuses(List<QueueNodeRuntimeConfig> nodes);

    void syncAgentStatus(AgentQueueRuntimeStatus status);
}
