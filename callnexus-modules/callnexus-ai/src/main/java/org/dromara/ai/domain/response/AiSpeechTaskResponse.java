package org.dromara.ai.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

@Data
public class AiSpeechTaskResponse {
    private Long id;
    private String taskType;
    private String businessType;
    private Long businessId;
    private Long providerId;
    private String providerType;
    private String voiceName;
    private String textContent;
    private Long inputMediaId;
    private Long outputMediaId;
    private String status;
    private Integer retryCount;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Date createTime;
}
