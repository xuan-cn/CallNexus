package org.dromara.openapi.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.openapi.domain.response.OpenApiEventResponse;
import org.dromara.openapi.security.OpenApiContext;
import org.dromara.openapi.service.OpenApiEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/openapi/v1/events")
@RequiredArgsConstructor
public class OpenApiEventController {
    private final OpenApiEventService eventService;

    @GetMapping
    public List<OpenApiEventResponse> list(@RequestParam(required = false, name = "after_id") Long afterId,
                                           @RequestParam(required = false, name = "event_type") String eventType,
                                           @RequestParam(required = false, name = "business_call_id") String businessCallId,
                                           @RequestParam(required = false, name = "page_size") Integer pageSize) {
        OpenApiContext.requireScope("event.subscribe");
        return eventService.list(OpenApiContext.require().applicationId(), afterId, eventType, businessCallId, pageSize);
    }
}
