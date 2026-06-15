package org.dromara.outbound.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

@Data
public class OutboundBlacklistResponse {
    private Long id;
    private String scopeType;
    private Long taskId;
    private String taskName;
    private String originalPhone;
    private String normalizedPhone;
    private String reason;
    private String source;
    private LocalDateTime effectiveAt;
    private LocalDateTime expiresAt;
    private Boolean enabled;
    private boolean active;
    private Date createTime;
}
