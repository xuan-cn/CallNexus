package org.dromara.ai.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AiIntentRequest {
    private Long groupId;
    @NotBlank @Size(max = 64) private String intentCode;
    @NotBlank @Size(max = 128) private String intentName;
    @NotBlank @Size(max = 32) private String intentType;
    @Size(max = 500) private String description;
    @NotBlank @Size(max = 32) private String actionType;
    private String actionConfigJson;
    @Size(max = 2000) private String responseTemplate;
    @DecimalMin("0") @DecimalMax("1") private BigDecimal confidenceThreshold;
    @Min(1) @Max(10000) private Integer priority;
    private Boolean confirmationRequired;
    private Boolean enabled;
    private List<@Valid AiIntentUtteranceRequest> utterances;
    private List<Long> agentIds;
}
