package org.dromara.call.service;

import org.dromara.call.domain.TelephonyEvent;

/**
 * 队列事件（mod_callcenter CUSTOM 事件）处理服务。
 *
 * <p>负责把 callcenter::* subclass 事件落库为通话时间线节点，
 * 并在坐席接听时记录实际接听队列，用于通话详情展示和话后整理时长计算。
 */
public interface QueueEventApplicationService {

    /**
     * 处理一个 mod_callcenter 队列事件。
     *
     * @param event 已识别为队列事件的 TelephonyEvent（eventName=CUSTOM，eventSubclass 以 callcenter:: 开头）
     */
    void handleQueueEvent(TelephonyEvent event);

    /**
     * 当 ESL CHANNEL_BRIDGE 事件发生且关联的业务通话来自队列时，记录"坐席接听"节点，
     * 并把实际接听队列写入业务通话主记录，供话后整理时长计算和详情展示。
     *
     * <p>由 {@link org.dromara.call.service.impl.TelephonyEventHandlerImpl} 在 BRIDGE 时调用。
     * 实现内部会判断该 session 是否为队列来电（有 QUEUE_IN 时间线节点），非队列来电直接返回。
     *
     * @param channelUuid 桥接坐席腿的 channel uuid
     * @return 实际接听队列 ID；无队列来电或未关联到队列返回 null
     */
    Long recordAgentAnswerOnBridge(String channelUuid);

    /**
     * 在业务通话聚合结束（所有通话腿挂断）时，判断队列来电是否未被接听，
     * 按 hangup_cause 推断并落库 QUEUE_TIMEOUT（队列等待超时）或 ABANDON（主叫放弃）节点。
     *
     * <p>由 {@link org.dromara.call.service.impl.CallRecordApplicationServiceImpl} 在 session 聚合结束时调用。
     * 仅对队列来电（已有 QUEUE_IN 时间线节点）且无 AGENT_ANSWER 的通话生效。
     *
     * @param sessionId    业务通话 ID
     * @param channelUuid  入站腿 channel uuid（用于落库关联）
     * @param hangupCause  最终挂断原因
     */
    void recordQueueTerminationIfUnanswered(Long sessionId, String channelUuid, String hangupCause);
}
