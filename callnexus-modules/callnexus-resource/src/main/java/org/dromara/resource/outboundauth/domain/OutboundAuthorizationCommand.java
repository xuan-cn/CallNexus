package org.dromara.resource.outboundauth.domain;

public record OutboundAuthorizationCommand(
    String tenantId,
    String sourceType,
    Long nodeId,
    String sipDomain,
    String switchIpv4,
    Long agentId,
    Long userId,
    Long skillGroupId,
    String callerExtension,
    String calleeNumber,
    Long callerNumberId,
    Long outboundLinePolicyId,
    Long taskId,
    Long memberId,
    Long customerId
) {
    public OutboundAuthorizationCommand(
        String tenantId,
        String sourceType,
        Long nodeId,
        String sipDomain,
        String switchIpv4,
        Long agentId,
        Long userId,
        Long skillGroupId,
        String callerExtension,
        String calleeNumber,
        Long callerNumberId,
        Long taskId,
        Long memberId,
        Long customerId
    ) {
        this(tenantId, sourceType, nodeId, sipDomain, switchIpv4, agentId, userId, skillGroupId,
            callerExtension, calleeNumber, callerNumberId, null, taskId, memberId, customerId);
    }
}
