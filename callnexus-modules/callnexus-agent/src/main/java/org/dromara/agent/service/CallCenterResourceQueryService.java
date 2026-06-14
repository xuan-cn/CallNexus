package org.dromara.agent.service;

/**
 * 呼叫中心资源查询契约。
 *
 * <p>队列事件处理（call 模块）需要根据 FreeSWITCH 上报的队列编码（{@code CC-Queue}，形如 {@code Q01@default}）
 * 和坐席标识（{@code CC-Agent}，形如 {@code 分机@域名}）反查业务实体。队列和坐席数据都归属 agent 模块，
 * 因此契约定义和实现都在 agent 模块，供 call 模块单向依赖调用，避免循环依赖。
 */
public interface CallCenterResourceQueryService {

    /**
     * 根据队列编码和 FreeSWITCH 节点查询队列信息。
     *
     * @param queueCodeWithProfile FreeSWITCH 上报的队列编码，可能带 @default 后缀
     * @param nodeId               FreeSWITCH 节点 ID
     * @return 队列信息；未找到返回 null
     */
    QueueInfo findQueueByCode(String queueCodeWithProfile, Long nodeId);

    /**
     * 根据队列 ID 查询队列信息，用于已知 handling_queue_id 时回查话后整理时长等参数。
     *
     * @param queueId 队列 ID
     * @return 队列信息；未找到返回 null
     */
    QueueInfo findQueueById(Long queueId);

    /**
     * 根据坐席标识（分机@域名）和 FreeSWITCH 节点查询坐席 ID。
     *
     * @param agentWithDomain FreeSWITCH 上报的坐席标识，形如 1001@192.168.244.128
     * @param nodeId          FreeSWITCH 节点 ID
     * @return 坐席 ID；未找到返回 null
     */
    Long findAgentIdByIdentity(String agentWithDomain, Long nodeId);

    /**
     * 队列基本信息。
     *
     * @param queueId        队列 ID
     * @param queueCode      队列编码
     * @param queueName      队列名称
     * @param wrapUpSeconds  话后整理时长（秒）
     */
    record QueueInfo(Long queueId, String queueCode, String queueName, Integer wrapUpSeconds) {
    }
}
