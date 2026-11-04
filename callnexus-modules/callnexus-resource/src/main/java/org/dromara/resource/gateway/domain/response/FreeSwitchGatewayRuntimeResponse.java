package org.dromara.resource.gateway.domain.response;

import java.time.Instant;

public record FreeSwitchGatewayRuntimeResponse(Long id, String status, String registrationState,
                                              String probeState, String detail, Instant checkedAt) {
}
