package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OpenApiEventResponse {
    private String eventId;
    private String eventType;
    private String businessCallId;
    private Long nodeId;
    private LocalDateTime occurredAt;
    private Map<String, Object> data = new LinkedHashMap<>();
}
