package org.dromara.agent.service;

/**
 * 队列记忆坐席登记表。
 *
 * <p>开启 {@code stickyAgentEnabled} 的队列在客户号码下次进入时，会优先桥接到上次接听的坐席。
 * 记忆数据按租户+队列+主叫号码三维写入 Redis，由 {@link #recordStickyAgent} 维护、{@link #findStickyAgentTarget} 查询。
 *
 * <p>查询时会回查该坐席当前在线状态（{@code IDLE} 才命中）、所属节点上的 SIP 分机是否启用，
 * 命中后返回 FreeSWITCH 桥接所需的 {@code extension@sipDomain} 字符串。
 */
public interface StickyAgentRegistry {

    /**
     * 查询记忆坐席桥接目标。
     *
     * @param tenantId     租户 ID
     * @param queueId      队列 ID
     * @param callerNumber 客户主叫号码
     * @param nodeId       客户接入的 FreeSWITCH 节点
     * @return 形如 {@code 1001@example.com} 的桥接目标；无记忆/不可用返回 null
     */
    String findStickyAgentTarget(String tenantId, Long queueId, String callerNumber, Long nodeId);

    /**
     * 在坐席成功接听后登记本次接听的坐席，供后续同主叫复呼时直拨。
     *
     * @param tenantId     租户 ID
     * @param queueId      队列 ID
     * @param callerNumber 客户主叫号码
     * @param agentId      实际接听坐席 ID
     */
    void recordStickyAgent(String tenantId, Long queueId, String callerNumber, Long agentId);
}