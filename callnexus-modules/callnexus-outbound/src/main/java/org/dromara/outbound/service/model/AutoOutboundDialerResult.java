package org.dromara.outbound.service.model;

public record AutoOutboundDialerResult(
    int tenantCount,
    int claimedCount,
    int submittedCount,
    int cancelledCount,
    int failedCount
) {
    public String summary() {
        return "租户=" + tenantCount + "，领取调度单=" + claimedCount + "，提交呼叫=" + submittedCount
            + "，取消=" + cancelledCount + "，失败=" + failedCount;
    }
}
