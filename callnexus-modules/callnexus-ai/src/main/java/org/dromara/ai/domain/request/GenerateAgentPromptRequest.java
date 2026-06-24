package org.dromara.ai.domain.request;

import lombok.Data;

import java.util.List;

@Data
public class GenerateAgentPromptRequest {
    private Long templateId;
    private List<Long> nodeGroupIds;
}
