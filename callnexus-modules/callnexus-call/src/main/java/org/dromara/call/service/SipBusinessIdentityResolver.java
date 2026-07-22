package org.dromara.call.service;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.sip.domain.response.SipAccountRealtimeResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;

/**
 * Resolves a SIP transport identity back to the business extension used by CallNexus.
 */
@Service
@RequiredArgsConstructor
public class SipBusinessIdentityResolver {
    private static final String INTERNAL_CHANNEL_PREFIX = "sofia/internal/";
    private static final String USER_CHANNEL_PREFIX = "user/";

    private final SipAccountQueryService sipAccountQueryService;

    public String resolveBusinessNumber(Long nodeId, String value) {
        String extension = resolveExtension(nodeId, value);
        return extension == null ? value : extension;
    }

    public String resolveExtension(Long nodeId, String value) {
        String identity = endpointIdentity(value);
        if (nodeId == null || identity == null) {
            return null;
        }
        SipAccountRealtimeResponse account = sipAccountQueryService.findEnabledByNodeAndIdentity(nodeId, identity);
        return account == null ? null : account.getExtension();
    }

    private String endpointIdentity(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String identity = value.trim();
        if (identity.regionMatches(true, 0, INTERNAL_CHANNEL_PREFIX, 0, INTERNAL_CHANNEL_PREFIX.length())) {
            identity = identity.substring(INTERNAL_CHANNEL_PREFIX.length());
        } else if (identity.regionMatches(true, 0, USER_CHANNEL_PREFIX, 0, USER_CHANNEL_PREFIX.length())) {
            identity = identity.substring(USER_CHANNEL_PREFIX.length());
        }
        int at = identity.indexOf('@');
        if (at > 0) {
            identity = identity.substring(0, at);
        }
        return StringUtils.isBlank(identity) ? null : identity;
    }
}
