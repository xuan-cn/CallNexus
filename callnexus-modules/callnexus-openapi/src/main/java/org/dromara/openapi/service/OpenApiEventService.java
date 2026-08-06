package org.dromara.openapi.service;

import org.dromara.call.domain.event.CallLifecycleEvent;
import org.dromara.openapi.domain.response.OpenApiEventResponse;

import java.util.List;

public interface OpenApiEventService {
    void publish(CallLifecycleEvent source);
    List<OpenApiEventResponse> list(Long applicationId, Long afterId, String eventType,
                                    String businessCallId, Integer pageSize);
}
