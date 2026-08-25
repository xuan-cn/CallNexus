package org.dromara.outbound.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class AutoOutboundSourceResponse {
    private Long id;
    private Long taskId;
    private Long importTaskId;
    private String importTaskName;
    private Long importBatchId;
    private String customerType;
    private String tags;
    private Long skillGroupId;
    private Long agentId;
    private String assignmentState;
    private String phoneStrategy;
    private String phoneLabel;
    private Boolean enabled;
    private String filterSummary;
    private Date createTime;
}
