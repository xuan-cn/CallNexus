package org.dromara.resource.outboundauth.domain;

public record OutboundAuthorizationRejection(
    String code,
    String message
) {
}
