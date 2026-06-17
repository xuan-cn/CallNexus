package org.dromara.agent.domain.response;

import java.util.Date;
import lombok.Data;

@Data
public class AgentResponse {
    private Long id;
    private String agentCode;
    private String agentName;
    private Long userId;
    private Long callerNumberId;
    private Long sipAccountId;
    private String sipExtension;
    private String sipDisplayName;
    private String sipDomain;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
