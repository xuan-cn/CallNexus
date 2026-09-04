package org.dromara.ai.domain.request;

import lombok.Data;

@Data
public class AiIntentQuery {
    private String keyword;
    private Long groupId;
    private Boolean ungrouped;
    private String intentType;
    private Boolean enabled;
    private Long agentId;
}
