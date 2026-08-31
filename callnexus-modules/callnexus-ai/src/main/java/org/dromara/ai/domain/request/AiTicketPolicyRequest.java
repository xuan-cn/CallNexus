package org.dromara.ai.domain.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AiTicketPolicyRequest {
    private Boolean enabled = false;
    private String creationMode = "DRAFT_REVIEW";
    private Long ticketTemplateId;
    private List<String> triggerTypes = List.of("CALL_ENDED");
    private List<String> includeIntents = List.of();
    private List<String> excludeIntents = List.of();
    @DecimalMin("0.0") @DecimalMax("1.0")
    private BigDecimal confidenceThreshold = new BigDecimal("0.8");
    private String missingRequiredAction = "KEEP_DRAFT";
    private String duplicatePolicy = "MERGE_PENDING";
    @Min(1) @Max(720)
    private Integer duplicateWindowHours = 24;
    private String afterCreateAction = "CREATE_ONLY";
    private Long customerTemplateId;
    private Long defaultSkillGroupId;
    private Map<String, Object> defaultValues = new LinkedHashMap<>();
}
