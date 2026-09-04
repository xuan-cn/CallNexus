package org.dromara.ai.domain.response;

import lombok.Data;
import java.util.Date;

@Data
public class AiWorkflowResponse {
    private Long id;
    private String workflowCode;
    private String workflowName;
    private String sceneType;
    private String description;
    private Boolean enabled;
    private Integer version;
    private Long draftVersionId;
    private Integer draftVersionNo;
    private Long publishedVersionId;
    private Integer publishedVersionNo;
    private Integer bindingCount;
    private Date updateTime;
}
