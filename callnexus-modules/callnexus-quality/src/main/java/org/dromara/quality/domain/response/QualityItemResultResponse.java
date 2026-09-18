package org.dromara.quality.domain.response;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class QualityItemResultResponse {
    private Long id; private Long templateItemId; private String itemCode; private String itemName;
    private String result; private Integer scoreChange; private BigDecimal confidence; private String reason; private String evidenceJson;
    private Boolean manuallyModified; private String modificationReason;
}
