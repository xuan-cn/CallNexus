package org.dromara.quality.domain.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
@Data
public class QualityTaskAssignRequest { @NotNull private Long reviewerId; }
