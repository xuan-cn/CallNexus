package org.dromara.ai.domain.response;

import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
public class AiMessageResponse {
    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private String sourceType;
    private String status;
    private String failureReason;
    private Date createTime;
    private List<AiCitationResponse> citations;
}
