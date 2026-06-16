package org.dromara.resource.event.businesshours;

public record BusinessHoursRouteEvaluatedEvent(
    String tenantId,
    String businessCallId,
    String channelUuid,
    Long planId,
    boolean inBusinessHours,
    String reason,
    String timezone,
    String targetType,
    String target
) {
}
