package org.dromara.resource.sip.service;

import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.domain.response.SipRegistrationConfigResponse;
import org.dromara.resource.sip.domain.response.SipAccountRealtimeResponse;

public interface SipAccountQueryService {
    boolean existsEnabled(Long sipAccountId);
    SipAccountResponse get(Long id);
    SipRegistrationConfigResponse getRegistrationConfig(Long id);
    SipAccountRealtimeResponse findEnabledByNodeAndExtension(Long nodeId, String extension);
}
