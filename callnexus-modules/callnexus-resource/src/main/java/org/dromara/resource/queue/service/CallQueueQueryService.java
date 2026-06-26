package org.dromara.resource.queue.service;

import org.dromara.resource.queue.domain.response.CallQueueDialplanResponse;

public interface CallQueueQueryService {

    /**
     * 兼容旧调用，不进行记忆坐席解析。
     */
    default CallQueueDialplanResponse findAvailableQueue(String tenantId, Long queueId, Long nodeId) {
        return findAvailableQueue(tenantId, queueId, nodeId, null);
    }

    /**
     * 查询节点上可用的呼叫队列，并补全 dialplan 渲染所需的可选项。
     *
     * @param tenantId       租户 ID
     * @param queueId        队列 ID
     * @param nodeId         FreeSWITCH 节点 ID
     * @param callerNumber   客户主叫号码；非空时按 {@code stickyAgentEnabled} 配置解析记忆坐席分机
     * @return 队列 dialplan 响应；不可用返回 {@code null}
     */
    CallQueueDialplanResponse findAvailableQueue(String tenantId, Long queueId, Long nodeId, String callerNumber);
}