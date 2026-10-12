package org.dromara.customer.ticket.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketUrgencyLevelResponse {
    private Long id;
    private String levelCode;
    private String levelName;
    private Integer priorityWeight;
    private String tagColor;
    private Boolean isDefault;
    private Boolean enabled;
    private Integer sortOrder;
    private String remark;
    private Integer version;
    private LocalDateTime createTime;
}
