package org.dromara.resource.businesshours.service;

import org.dromara.resource.businesshours.domain.request.BusinessHoursPlanRequest;
import org.dromara.resource.businesshours.domain.response.BusinessHoursEvaluation;
import org.dromara.resource.businesshours.domain.response.BusinessHoursPlanResponse;

import java.time.LocalDateTime;
import java.util.List;

public interface BusinessHoursApplicationService {
    List<BusinessHoursPlanResponse> list();
    BusinessHoursPlanResponse get(Long id);
    Long create(BusinessHoursPlanRequest request);
    void update(Long id, BusinessHoursPlanRequest request);
    void delete(Long id);
    BusinessHoursEvaluation evaluate(Long id, LocalDateTime evaluatedAt);
}
