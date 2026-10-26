package org.dromara.resource.sip.domain.response;

import java.time.LocalDateTime;
import java.util.Date;
import lombok.Data;

@Data
public class SipAccountResponse {
    private Long id;
    private Long nodeId;
    private String nodeName;
    private String extension;
    private String authUsername;
    private String displayName;
    private String domain;
    private Boolean enabled;
    private String securityStatus;
    private Integer recentFailureCount;
    private LocalDateTime lastFailureAt;
    private String lastFailureIp;
    private LocalDateTime lockedUntil;
    private String lockReason;
    private Integer version;
    private Date createTime;
}
