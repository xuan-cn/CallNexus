package org.dromara.report.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QueueReportResponse {
    private Long queueId;
    private String queueCode;
    private String queueName;
    private String skillGroupName;
    private Long enteredCount;
    private Long answeredCount;
    private Long abandonedCount;
    private Long timeoutCount;
    private BigDecimal answerRate;
    private BigDecimal abandonRate;
    private Long averageWaitSeconds;
    private Long maximumWaitSeconds;
    private BigDecimal serviceLevel;
}
