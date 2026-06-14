package org.dromara.outbound.domain.response;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class OutboundTaskStatisticsResponse {
    private Long taskId;
    private long totalCount;
    private long pendingCount;
    private long claimedCount;
    private long dialingCount;
    private long completedCount;
    private long retryCount;
    private long dialedCount;
    private long connectedCount;
    private double completionRate;
    private double connectionRate;
    private Map<String, Long> resultDistribution = new LinkedHashMap<>();
}
