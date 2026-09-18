package org.dromara.quality.domain.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class QualityAlertRuleRequest {
    @NotBlank private String ruleName;
    @NotBlank private String periodType;
    @NotBlank private String metricCode;
    @NotBlank private String compareOperator;
    @NotNull @DecimalMin("0") private BigDecimal thresholdValue;
    @NotBlank private String scopeType;
    private Long scopeId;
    private String scopeName;
    @NotNull private Boolean enabled;
    private String remark;
}
