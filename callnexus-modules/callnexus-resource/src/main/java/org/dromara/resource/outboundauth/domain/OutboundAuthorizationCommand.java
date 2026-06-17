package org.dromara.resource.outboundauth.domain;

public record OutboundAuthorizationCommand(
    String tenantId,
    String sourceType,
    Long nodeId,
    String sipDomain,
    String switchIpv4,
    Long agentId,
    Long userId,
    String callerExtension,
    String calleeNumber,
    Long callerNumberId,
    Long taskId,
    Long memberId,
    Long customerId
) {
}
