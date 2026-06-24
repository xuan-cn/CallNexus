package org.dromara.ai.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiGeneratedMediaResponse {
    private Long id;
    private String businessType;
    private Long businessId;
    private Long mediaId;
    private Long taskId;
    private String generationStatus;
    private LocalDateTime generatedAt;
    private String failureReason;
    private String syncedPath;
}
