package org.dromara.ai.domain.request;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AiWorkflowTestStartRequest {
    private Long agentId;
    private Map<String, Object> variables = new LinkedHashMap<>();
}
