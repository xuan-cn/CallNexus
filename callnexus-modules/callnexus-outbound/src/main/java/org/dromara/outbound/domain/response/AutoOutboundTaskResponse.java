package org.dromara.outbound.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class AutoOutboundTaskResponse {
    private Long id;
    private String taskCode;
    private String taskName;
    private String taskType;
    private String status;
    private String description;
    private Long callerNumberId;
    private String dialMode;
    private String targetType;
    private Long targetId;
    private Long skillGroupId;
    private Integer concurrencyLimit;
    private Integer callsPerMinute;
    private Integer maxCallsPerDay;
    private Integer maxCallsTotal;
    private Integer minCallIntervalMinutes;
    private String scheduleTimezone;
    private Boolean resultWritebackEnabled;
    private String connectedTag;
    private String failedTag;
    private long totalCount;
    private long pendingCount;
    private long completedCount;
    private long activeCount;
    private LocalDateTime lastScheduledAt;
    private String lastScheduleSummary;
    private Integer version;
    private Date createTime;
    private List<CallWindow> callWindows = new ArrayList<>();
    private List<RetryRule> retryRules = new ArrayList<>();

    @Data
    public static class CallWindow {
        private Long id;
        private List<Integer> weekdays = new ArrayList<>();
        private LocalTime startTime;
        private LocalTime endTime;
        private Boolean enabled;
        private Integer sortOrder;
    }

    @Data
    public static class RetryRule {
        private Long id;
        private String resultCode;
        private Boolean retryEnabled;
        private Integer maxRetryCount;
        private Integer retryIntervalMinutes;
        private Integer sortOrder;
    }
}
