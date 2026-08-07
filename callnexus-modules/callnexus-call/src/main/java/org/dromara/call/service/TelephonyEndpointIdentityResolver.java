package org.dromara.call.service;

import lombok.RequiredArgsConstructor;
import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.TelephonyEvent;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TelephonyEndpointIdentityResolver {
    private static final String INTERNAL_CHANNEL_PREFIX = "sofia/internal/";
    private static final String USER_CHANNEL_PREFIX = "user/";

    private final SipBusinessIdentityResolver sipBusinessIdentityResolver;

    public String resolveAuthoritativeExtension(TelephonyEvent event) {
        if (event == null) {
            return null;
        }
        String channelName = event.headers().get(EslHeaders.CHANNEL_NAME);
        String channelExtension = firstResolved(event.nodeId(), identityFromChannelName(channelName));
        if (channelExtension != null || isExternalCounterpartyChannel(channelName)) {
            return channelExtension;
        }
        return firstResolved(event.nodeId(),
            event.headers().get(EslHeaders.VARIABLE_DIALED_USER),
            event.headers().get(EslHeaders.VARIABLE_DIALLED_USER));
    }

    public String resolveChannelExtension(TelephonyEvent event) {
        if (event == null) {
            return null;
        }
        if (isExternalCounterpartyChannel(event)) {
            return null;
        }
        String extension = resolveAuthoritativeExtension(event);
        if (extension != null) {
            return extension;
        }
        // External and gateway channels represent the PSTN counterparty. Their callee and
        // destination fields may contain the transferred extension, but that does not make
        // the external channel an agent leg.
        extension = firstResolved(event.nodeId(),
            event.headers().get(EslHeaders.CALLER_CALLEE_ID_NUMBER),
            event.headers().get(EslHeaders.VARIABLE_SIP_REQ_USER),
            event.headers().get(EslHeaders.VARIABLE_SIP_TO_USER),
            event.headers().get(EslHeaders.CC_AGENT));
        if (extension != null) {
            return extension;
        }
        String callDirection = event.headers().get(EslHeaders.CALL_DIRECTION);
        if ("inbound".equalsIgnoreCase(callDirection)) {
            return resolveKnownExtension(event.nodeId(), event.callerNumber());
        }
        if ("outbound".equalsIgnoreCase(callDirection)) {
            return firstResolved(event.nodeId(), event.destinationNumber(), event.callerNumber());
        }
        return firstResolved(event.nodeId(), event.destinationNumber(), event.callerNumber());
    }

    public boolean isExternalCounterpartyChannel(TelephonyEvent event) {
        return event != null && isExternalCounterpartyChannel(event.headers().get(EslHeaders.CHANNEL_NAME));
    }

    private boolean isExternalCounterpartyChannel(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return false;
        }
        return channelName.regionMatches(true, 0, "sofia/external/", 0, "sofia/external/".length())
            || channelName.regionMatches(true, 0, "sofia/gateway/", 0, "sofia/gateway/".length());
    }

    public String resolveKnownExtension(Long nodeId, String identity) {
        return sipBusinessIdentityResolver.resolveExtension(nodeId, identity);
    }

    public String resolveBusinessNumber(Long nodeId, String identity) {
        return sipBusinessIdentityResolver.resolveBusinessNumber(nodeId, identity);
    }

    private String firstResolved(Long nodeId, String... identities) {
        for (String identity : identities) {
            String extension = resolveKnownExtension(nodeId, identity);
            if (extension != null && !extension.isBlank()) {
                return extension;
            }
        }
        return null;
    }

    private String identityFromChannelName(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return null;
        }
        String identity;
        if (channelName.regionMatches(true, 0, INTERNAL_CHANNEL_PREFIX, 0, INTERNAL_CHANNEL_PREFIX.length())) {
            identity = channelName.substring(INTERNAL_CHANNEL_PREFIX.length());
        } else if (channelName.regionMatches(true, 0, USER_CHANNEL_PREFIX, 0, USER_CHANNEL_PREFIX.length())) {
            identity = channelName.substring(USER_CHANNEL_PREFIX.length());
        } else {
            return null;
        }
        int at = identity.indexOf('@');
        return at > 0 ? identity.substring(0, at) : identity;
    }
}
