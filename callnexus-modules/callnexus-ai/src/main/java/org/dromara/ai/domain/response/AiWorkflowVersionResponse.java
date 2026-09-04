package org.dromara.ai.domain.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Date;

@Data
public class AiWorkflowVersionResponse {
    private Long id;
    private Long workflowId;
    private Integer versionNo;
    private String versionName;
    private String status;
    private String definitionJson;
    private String definitionHash;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private Date createTime;
    private Date updateTime;
}
