package org.dromara.outbound.domain.response;

import lombok.Data;

import java.time.LocalDate;

@Data
public class OutboundDailyTrendResponse {
    private LocalDate date;
    private long attemptCount;
    private long answeredCount;
    private long connectedCount;
    private long customerCount;
    private long totalDurationSeconds;
    private long billableSeconds;
    private double answerRate;
    private double connectionRate;
}
