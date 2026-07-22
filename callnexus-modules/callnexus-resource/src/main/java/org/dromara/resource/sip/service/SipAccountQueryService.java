package org.dromara.resource.sip.service;

import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.domain.response.SipRegistrationConfigResponse;
import org.dromara.resource.sip.domain.response.SipAccountRealtimeResponse;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;

public interface SipAccountQueryService {
    boolean existsEnabled(Long sipAccountId);
    SipAccountResponse get(Long id);
    SipAccountResponse findEnabledById(Long id);
    SipRegistrationConfigResponse getRegistrationConfig(Long id);
    SipAccountRealtimeResponse findEnabledByNodeAndExtension(Long nodeId, String extension);
    SipAccountRealtimeResponse findEnabledByNodeAndIdentity(Long nodeId, String extensionOrAuthUsername);
    SipDirectoryAccountResponse findDirectoryAccount(String tenantId, String domain, String extension);
    SipDirectoryAccountResponse findDirectoryAccountForAuth(String tenantId, String domain, String authUsername);
    SipDirectoryAccountResponse findDirectoryAccountByExtension(String tenantId, String domain, String extension);
}
