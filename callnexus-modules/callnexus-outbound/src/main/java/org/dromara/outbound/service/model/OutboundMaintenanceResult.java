package org.dromara.outbound.service.model;

public record OutboundMaintenanceResult(
    int tenantCount,
    int taskCount,
    int recoveredMemberCount,
    int reactivatedTaskCount,
    int assignedDueRetryCount,
    int restoredBlacklistMemberCount
) {

    public String summary() {
        return "租户=" + tenantCount
            + "，任务=" + taskCount
            + "，恢复异常名单=" + recoveredMemberCount
            + "，激活到期重呼任务=" + reactivatedTaskCount
            + "，自动分配到期重呼=" + assignedDueRetryCount
            + "，恢复过期黑名单名单=" + restoredBlacklistMemberCount;
    }
}
