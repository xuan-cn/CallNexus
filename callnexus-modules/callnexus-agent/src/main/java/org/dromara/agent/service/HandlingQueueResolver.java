package org.dromara.agent.service;

/**
 * 业务通话实际接听队列解析契约。
 *
 * <p>agent 模块在计算话后整理时长时，需要知道本次活跃通话实际接听的是哪条队列。
 * 该信息保存在 call 模块的业务通话主记录（cc_call_session.handling_queue_id）中，
 * 为避免 agent 反向依赖 call 模块，由 agent 定义本契约，call 模块提供实现。
 */
public interface HandlingQueueResolver {

    /**
     * 根据活跃通话 channel UUID 查询本次实际接听队列的话后整理时长。
     *
     * @param channelUuid 活跃通话 channel UUID（坐席 activeCall.callId）
     * @return 话后整理秒数；未关联到队列或通话不存在返回 null
     */
    Integer resolveWrapUpSeconds(String channelUuid);
}
