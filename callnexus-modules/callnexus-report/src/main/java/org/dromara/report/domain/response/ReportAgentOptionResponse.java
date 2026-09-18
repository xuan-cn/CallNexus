package org.dromara.report.domain.response;

import lombok.Data;

@Data
public class ReportAgentOptionResponse {
    private Long id;
    private String agentCode;
    private String agentName;
    private String extension;
    private Boolean enabled;
}
