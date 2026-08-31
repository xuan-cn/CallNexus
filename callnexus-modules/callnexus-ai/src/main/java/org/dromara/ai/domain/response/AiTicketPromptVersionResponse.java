package org.dromara.ai.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class AiTicketPromptVersionResponse {
    private Long id;
    private Integer versionNo;
    private String versionName;
    private String status;
    private String protocolVersion;
    private String promptHash;
    private Long publishedBy;
    private Date publishedAt;
    private Date createTime;
}
