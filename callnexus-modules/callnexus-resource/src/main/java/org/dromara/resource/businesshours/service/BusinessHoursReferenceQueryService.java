package org.dromara.resource.businesshours.service;

public interface BusinessHoursReferenceQueryService {
    boolean isReferencedByPublishedIvr(String tenantId, Long planId);
}
