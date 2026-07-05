package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DispatchCallTaskResponse {
    private Long id;
    private String businessCallId;
    private Long nodeId;
    private String operatorExtension;
    private String operatorLegUuid;
    private Long mediaAssetId;
    private String mediaName;
    private String mediaPath;
    private Boolean intercomTalking;
    private String taskType;
    private String taskState;
    private Integer totalCount;
    private Integer answeredCount;
    private Integer failedCount;
    private Integer cancelledCount;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private List<DispatchCallTargetResponse> targets;
}
