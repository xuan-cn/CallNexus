package org.dromara.agent.domain.request;

import lombok.Data;

@Data
public class AgentPageQuery {
    private String agentCode;
    private String agentName;
    private Boolean enabled;
}
