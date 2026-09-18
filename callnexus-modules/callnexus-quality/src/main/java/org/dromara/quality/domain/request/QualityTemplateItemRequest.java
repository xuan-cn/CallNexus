package org.dromara.quality.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class QualityTemplateItemRequest {
    private Long id;
    @NotBlank private String dimensionCode;
    @NotBlank private String dimensionName;
    @NotBlank private String itemCode;
    @NotBlank private String itemName;
    @NotBlank private String itemType;
    @NotNull private Integer scoreValue;
    private Boolean fatalFlag = false;
    private Boolean allowNotApplicable = false;
    private Boolean evidenceRequired = false;
    private Boolean aiReviewEnabled = true;
    @DecimalMin("0.0") @DecimalMax("1.0") private BigDecimal aiConfidenceThreshold = new BigDecimal("0.700");
    @Size(max = 1000) private String aiPromptHint;
    @Size(max = 1000) private String ruleDescription;
    private Integer sortOrder = 0;
}
