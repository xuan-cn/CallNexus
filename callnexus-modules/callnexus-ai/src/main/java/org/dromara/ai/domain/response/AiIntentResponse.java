package org.dromara.ai.domain.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AiIntentResponse {
    private Long id;
    private String intentCode;
    private String intentName;
    private String intentType;
    private String description;
    private String actionType;
    private String actionConfigJson;
    private String responseTemplate;
    private BigDecimal confidenceThreshold;
    private Integer priority;
    private Boolean confirmationRequired;
    private Boolean enabled;
    private Integer version;
    private List<AiIntentUtteranceResponse> utterances;
    private List<Long> agentIds;
    private List<String> agentNames;
}
