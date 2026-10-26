package org.dromara.report.domain.response;

import lombok.Data;

@Data
public class ReportSkillGroupOptionResponse {
    private Long id;
    private String groupCode;
    private String groupName;
    private Boolean enabled;
}
