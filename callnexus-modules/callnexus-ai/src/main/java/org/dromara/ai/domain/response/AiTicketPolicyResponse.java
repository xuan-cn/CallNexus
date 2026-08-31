package org.dromara.ai.domain.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AiTicketPolicyResponse {
    private Long id;
    private Long aiAgentId;
    private Boolean enabled;
    private String creationMode;
    private Long ticketTemplateId;
    private List<String> triggerTypes = List.of();
    private List<String> includeIntents = List.of();
    private List<String> excludeIntents = List.of();
    private BigDecimal confidenceThreshold;
    private String missingRequiredAction;
    private String duplicatePolicy;
    private Integer duplicateWindowHours;
    private String afterCreateAction;
    private Long customerTemplateId;
    private Long defaultSkillGroupId;
    private Map<String, Object> defaultValues = new LinkedHashMap<>();
    private Long activePromptVersionId;
    private Integer version;
}
