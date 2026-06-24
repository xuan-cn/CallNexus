package org.dromara.agent.domain.request;

import lombok.Data;

import java.util.List;

@Data
public class BatchGenerateAgentPromptRequest {
    private List<Long> agentIds;
    private String agentCode;
    private String agentName;
    private Boolean enabled;
    private Boolean onlyMissing;
    private Long templateId;
    private List<Long> nodeGroupIds;
}
