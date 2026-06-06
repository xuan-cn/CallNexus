package org.dromara.agent.domain.response;

import java.util.Date;
import lombok.Data;

@Data
public class AgentResponse {
    private Long id;
    private String agentCode;
    private String agentName;
    private Long userId;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
