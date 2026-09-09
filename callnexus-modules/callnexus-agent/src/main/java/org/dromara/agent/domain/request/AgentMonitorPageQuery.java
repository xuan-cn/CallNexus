package org.dromara.agent.domain.request;

import lombok.Data;

import java.time.LocalDate;

@Data
public class AgentMonitorPageQuery {
    private String keyword;
    private Long skillGroupId;
    private String status;
    private Boolean enabled;
    private LocalDate beginDate;
    private LocalDate endDate;
}
