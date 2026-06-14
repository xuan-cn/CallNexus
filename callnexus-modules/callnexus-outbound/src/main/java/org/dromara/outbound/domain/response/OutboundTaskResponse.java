package org.dromara.outbound.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class OutboundTaskResponse {
    private Long id;
    private String taskCode;
    private String taskName;
    private String taskType;
    private String status;
    private String description;
    private long totalCount;
    private long pendingCount;
    private long completedCount;
    private Integer version;
    private Date createTime;
}
