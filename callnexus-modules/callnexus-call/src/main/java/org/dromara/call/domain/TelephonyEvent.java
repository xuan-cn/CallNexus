package org.dromara.call.domain;

import java.util.Map;

public record TelephonyEvent(Long nodeId, String eventName, String uuid, String callerNumber,
                             String destinationNumber, String hangupCause, Map<String, String> headers) {
}
