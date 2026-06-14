package org.dromara.resource.event.queue;

/**
 * 进入呼叫队列信号事件。
 *
 * <p>当 FreeSWITCH 通过动态 dialplan 请求命中呼叫队列路由（{@code callnexus_route_type=QUEUE}）时，
 * 由 {@code QueueDialplanRouteHandler} 发布本事件。call 模块通过 @EventListener 消费，
 * 把"进入队列"作为通话时间线的第一个节点落库到 {@code cc_call_event}。
 *
 * <p>该信号是队列事件轨迹重建的基础，因为 FreeSWITCH 1.10.x 的 mod_callcenter
 * 不通过 ESL CUSTOM 事件广播队列生命周期，但 dialplan xml-curl 请求是 FreeSWITCH 必然会发起的。
 */
public record QueueEntrySignalEvent(
    String tenantId,
    /** 业务通话 ID，对应 channel 变量 callnexus_business_call_id，等于入站腿 channel uuid */
    String businessCallId,
    /** 入站腿 channel uuid，对应 channel 变量 uuid */
    String channelUuid,
    /** 呼叫队列 ID */
    Long queueId,
    /** 队列编码（不含 @default 后缀） */
    String queueCode,
    /** 队列名称，用于时间线和详情展示 */
    String queueName,
    /** FreeSWITCH 节点 ID */
    Long nodeId
) {
}
