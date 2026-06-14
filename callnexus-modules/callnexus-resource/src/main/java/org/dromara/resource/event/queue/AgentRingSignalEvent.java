package org.dromara.resource.event.queue;

/**
 * 队列向坐席振铃信号事件。
 *
 * <p>当 FreeSWITCH mod_callcenter 给坐席振铃时，必定会通过 directory xml-curl 请求查询坐席信息，
 * 请求参数会携带 {@code cc_queue}、{@code cc_agent}、{@code cc_member_session_uuid}（= 入站腿 channel uuid）。
 * 由 {@code DirectoryUserXmlCurlHandler} 在识别到这些参数时发布本事件，
 * call 模块通过 @EventListener 消费，把"坐席振铃"节点落库到 {@code cc_call_event}。
 *
 * <p>该信号比 mod_callcenter 的 ESL 事件更可靠：directory 请求是 mod_callcenter 振铃坐席的必经路径，
 * 不受 FreeSWITCH 版本的事件广播机制影响。
 *
 * <p>RING_ALL 策略下会触发多次 directory 请求（同时给多个坐席振铃），
 * 每次请求都会发布本事件，时间线将展示多个"坐席振铃"节点。
 */
public record AgentRingSignalEvent(
    String tenantId,
    /** 队列成员对应的入站腿 channel uuid（cc_member_session_uuid），用于反查业务通话 session */
    String memberSessionUuid,
    /** FreeSWITCH 上报的队列编码，形如 QUEUE@default */
    String queueCode,
    /** FreeSWITCH 上报的坐席标识，形如 1001@192.168.244.128 */
    String agentIdentity,
    /** directory 请求的 action 字段，通常为 user_call */
    String action,
    /** FreeSWITCH 节点 ID */
    Long nodeId
) {
}
