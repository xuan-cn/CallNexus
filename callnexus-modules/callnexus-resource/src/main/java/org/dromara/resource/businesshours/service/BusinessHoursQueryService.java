package org.dromara.resource.businesshours.service;

import org.dromara.resource.businesshours.domain.response.BusinessHoursEvaluation;

import java.time.Instant;

public interface BusinessHoursQueryService {
    boolean isPlanAvailable(String tenantId, Long planId);
    BusinessHoursEvaluation evaluate(String tenantId, Long planId, Instant instant);
}
