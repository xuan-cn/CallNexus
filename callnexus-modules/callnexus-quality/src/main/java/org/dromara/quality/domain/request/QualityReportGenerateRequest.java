package org.dromara.quality.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class QualityReportGenerateRequest {
    @NotBlank private String periodType;
    private LocalDate periodEnd;
}
