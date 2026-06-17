package org.dromara.call.domain;

public record CallOriginateContext(
    String businessCallId,
    Long customerId,
    Long outboundTaskId,
    Long outboundMemberId,
    Long callerNumberId
) {
    public static CallOriginateContext empty() {
        return new CallOriginateContext(null, null, null, null, null);
    }
}
