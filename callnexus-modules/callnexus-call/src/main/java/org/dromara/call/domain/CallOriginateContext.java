package org.dromara.call.domain;

import java.util.Set;

public record CallOriginateContext(
    String businessCallId,
    Long customerId,
    Long outboundTaskId,
    Long outboundMemberId,
    Long callerNumberId,
    Long skillGroupId,
    Set<String> allowedOutboundPolicyCodes
) {
    public static CallOriginateContext empty() {
        return new CallOriginateContext(null, null, null, null, null, null, null);
    }
}
