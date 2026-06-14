package org.dromara.resource.queue.service;

import org.dromara.resource.queue.domain.response.CallQueueDialplanResponse;

public interface CallQueueQueryService {

    CallQueueDialplanResponse findAvailableQueue(String tenantId, Long queueId, Long nodeId);
}
