package org.dromara.ai.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AiIntentRecognitionResponse {
    private boolean matched;
    private Long intentId;
    private String intentCode;
    private String intentName;
    private String intentType;
    private String actionType;
    private String actionConfigJson;
    private String responseTemplate;
    private Boolean confirmationRequired;
    private BigDecimal confidence;
    private String matchMethod;
    private String reason;
    private Long latencyMs;
    private String rawResponse;
}
