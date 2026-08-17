package org.dromara.ai.domain.request;

import lombok.Data;

@Data
public class AiCallRecordQuery {

    private Integer pageNum;

    private Integer pageSize;

    private String participantNumber;

    private String callerNumber;

    private String calledNumber;

    private String agentExtension;

    private String callStatus;
}
