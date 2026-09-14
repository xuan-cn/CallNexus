package org.dromara.report.domain.response;

import lombok.Data;

@Data
public class OutboundTaskReportResponse {
    private Long taskId;
    private String taskName;
    private String taskType;
    private String status;
    private Long memberCount;
    private Long dialedMemberCount;
    private Long attemptCount;
    private Long answeredCount;
    private Long businessSuccessCount;
    private Double answerRate;
    private Double conversionRate;
}
