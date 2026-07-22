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
     * @param queueCodeWithProfile FreeSWITCH 上报的队列编码，可能带 {@code @default} 后缀
     * @param nodeId               FreeSWITCH 节点 ID
     * @return 队列信息；未找到返回 null
     */
    QueueInfo findQueueByCode(String queueCodeWithProfile, Long nodeId);

    /**
     * 根据队列 ID 查询队列信息，用于已知 handling_queue_id 时回查话后整理时长等参数。
     */
    QueueInfo findQueueById(Long queueId);

    /**
     * 根据队列 ID 和 FreeSWITCH 节点查询队列信息。
     *
     * <p>带节点时会解析接通提示音在该节点上的本地同步路径，供通话事件侧直接播放。
     */
    QueueInfo findQueueById(Long queueId, Long nodeId);

    /**
     * 根据坐席标识（分机@域名）和 FreeSWITCH 节点查询坐席 ID。
     */
    Long findAgentIdByIdentity(String agentWithDomain, Long nodeId);

    /**
     * 根据坐席标识（分机或 SIP 鉴权名，可带域名）和 FreeSWITCH 节点查询真实分机号。
     */
    String findAgentExtensionByIdentity(String agentWithDomain, Long nodeId);

    /**
     * 队列基础信息。
     *
     * @param queueId             队列 ID
     * @param queueCode           队列编码
     * @param queueName           队列名称
     * @param wrapUpSeconds       话后整理时长（秒）
     * @param maxWaitSeconds      最大等待时长（秒）
     * @param answerAction        接通时动作：NONE / PLAY_AGENT_NUMBER / PLAY_MEDIA
     * @param answerMediaId       接通提示音媒体 ID
     * @param answerMediaPath     接通提示音在节点上的本地路径
     * @param hangupKeyAction     挂机按键采集模式：NONE / AGENT / CALLER
     * @param stickyAgentEnabled  是否启用记忆坐席
     */
    record QueueInfo(Long queueId, String queueCode, String queueName, Integer wrapUpSeconds, Integer maxWaitSeconds,
                     String answerAction, Long answerMediaId, String answerMediaPath, String hangupKeyAction,
                     Boolean stickyAgentEnabled) {
    }
}
