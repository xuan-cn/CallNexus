package org.dromara.outbound.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AutoOutboundMonitorResponse {
    private Long taskId;
    private String taskStatus;
    private long pendingCount;
    private long scheduledCount;
    private long processingCount;
    private long dialingCount;
    private long completedCount;
    private long activeConcurrency;
    private long queuedCount;
    private long todayCallCount;
    private long todayAnsweredCount;
    private double todayAnswerRate;
    private List<FailureMetric> failureMetrics = new ArrayList<>();
    private String schedulerOwner;
    private LocalDateTime schedulerLeaseUntil;
    private LocalDateTime schedulerHeartbeatAt;
    private LocalDateTime lastScheduledAt;
    private String lastScheduleSummary;

    @Data
    public static class FailureMetric {
        private String category;
        private String categoryLabel;
        private long count;
        private boolean retryable;
    }
}
