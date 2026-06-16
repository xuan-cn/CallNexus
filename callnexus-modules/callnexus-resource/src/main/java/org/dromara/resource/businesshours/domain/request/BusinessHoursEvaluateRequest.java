package org.dromara.resource.businesshours.domain.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessHoursEvaluateRequest {
    private LocalDateTime evaluatedAt;
}
