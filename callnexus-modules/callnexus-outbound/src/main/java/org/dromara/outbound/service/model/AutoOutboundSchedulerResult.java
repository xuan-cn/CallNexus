package org.dromara.outbound.service.model;

public record AutoOutboundSchedulerResult(
    int tenantCount,
    int scannedTaskCount,
    int leasedTaskCount,
    int scheduledMemberCount,
    int recoveredDispatchCount,
    int completedTaskCount
) {
    public String summary() {
        return "租户=" + tenantCount + "，扫描任务=" + scannedTaskCount + "，取得租约=" + leasedTaskCount
            + "，生成调度单=" + scheduledMemberCount + "，恢复超时调度单=" + recoveredDispatchCount
            + "，完成任务=" + completedTaskCount;
    }
}
