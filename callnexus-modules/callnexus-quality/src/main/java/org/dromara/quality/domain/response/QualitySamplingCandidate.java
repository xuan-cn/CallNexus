package org.dromara.quality.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualitySamplingCandidate {
    private Long callSessionId;
    private String businessCallId;
    private String direction;
    private Long agentId;
    private String agentName;
    private String agentExtension;
    private Long queueId;
    private String queueName;
    private Long skillGroupId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private Integer satisfactionScore;
    private Boolean hasRecording;
    private Boolean hasTranscript;
    private Integer riskScore;
}
