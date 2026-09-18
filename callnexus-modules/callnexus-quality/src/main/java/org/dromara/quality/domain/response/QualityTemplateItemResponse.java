package org.dromara.quality.domain.response;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class QualityTemplateItemResponse {
    private Long id; private String dimensionCode; private String dimensionName;
    private String itemCode; private String itemName; private String itemType;
    private Integer scoreValue; private Boolean fatalFlag; private Boolean allowNotApplicable;
    private Boolean evidenceRequired; private Boolean aiReviewEnabled; private BigDecimal aiConfidenceThreshold;
    private String aiPromptHint; private String ruleDescription; private Integer sortOrder;
}
