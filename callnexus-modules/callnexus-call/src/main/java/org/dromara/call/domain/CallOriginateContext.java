package org.dromara.call.domain;

public record CallOriginateContext(
    Long customerId,
    Long outboundTaskId,
    Long outboundMemberId
) {
    public static CallOriginateContext empty() {
        return new CallOriginateContext(null, null, null);
    }
}
