package org.dromara.outbound.home;

import lombok.Data;

/**
 * 系统首页业务概览（客户 / 工单 / 话务）。
 */
@Data
public class HomeBusinessOverviewResponse {

    /** 客户总量 */
    private long customerTotal;
    /** 区间新增客户 */
    private long customerPeriodNew;
    /** 未分配客户 */
    private long customerUnassigned;

    /** 工单总量 */
    private long ticketTotal;
    /** 待处理 */
    private long ticketOpen;
    /** 处理中 */
    private long ticketProcessing;
    /** 已解决 */
    private long ticketResolved;
    /** 已关闭 */
    private long ticketClosed;
    /** 区间新建工单 */
    private long ticketPeriodNew;

    /** 区间呼入 */
    private long inboundCount;
    /** 区间呼出 */
    private long outboundCount;
    /** 区间接通（已应答会话） */
    private long answeredCount;
    /** 接通率（接通 / 呼入，百分比整数） */
    private int answerRate;

    /** 外呼任务名单总量 */
    private long outboundTaskTotal;
    /** 外呼任务已完成 */
    private long outboundTaskCompleted;
    /** 外呼完成率 */
    private int outboundCompletionRate;

    /** 未处理留言 */
    private long voicemailPending;
}
