package org.dromara.resource.sip.security.service;

import org.dromara.resource.sip.security.domain.request.SipRegistrationEventRequest;
import org.dromara.resource.sip.security.domain.response.SipRegistrationSecuritySummaryResponse;

public interface SipRegistrationSecurityService {
    void record(SipRegistrationEventRequest request);

    boolean isBlocked(String tenantId, Long nodeId, Long sipAccountId, String sourceIp);

    SipRegistrationSecuritySummaryResponse summary(Long sipAccountId);

    void manualLock(Long sipAccountId, String reason);

    void unlock(Long sipAccountId, String reason);
}
