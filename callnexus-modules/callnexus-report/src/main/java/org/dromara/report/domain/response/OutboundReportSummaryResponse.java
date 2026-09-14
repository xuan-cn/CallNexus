package org.dromara.report.domain.response;

import lombok.Data;

@Data
public class OutboundReportSummaryResponse {
    private Long memberCount;
    private Long dialedMemberCount;
    private Long undialedMemberCount;
    private Long attemptCount;
    private Long answeredCount;
    private Long businessSuccessCount;
    private Long retryAttemptCount;
    private Long totalTalkSeconds;
    private Double averageAttempts;
    private Double answerRate;
    private Double conversionRate;
}
