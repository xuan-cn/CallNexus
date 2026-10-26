package org.dromara.outbound.domain.response;

import lombok.Data;

@Data
public class ClickDialSummaryResponse {
    private Long totalCount;
    private Long uncalledCount;
    private Long processingCount;
    private Long followUpCount;
    private Long dueFollowUpCount;
    private Long completedCount;
    private Long dialedCount;
}
