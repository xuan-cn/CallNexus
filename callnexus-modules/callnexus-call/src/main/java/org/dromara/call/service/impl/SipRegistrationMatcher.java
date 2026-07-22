package org.dromara.call.service.impl;

import java.util.Set;

final class SipRegistrationMatcher {
    private SipRegistrationMatcher() {
    }

    static boolean isRegistered(Set<String> registeredIdentities, String extension, String authUsername) {
        if (registeredIdentities == null) return false;
        return (hasText(extension) && registeredIdentities.contains(extension))
            || (hasText(authUsername) && registeredIdentities.contains(authUsername));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
