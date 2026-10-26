package org.dromara.outbound.domain.response;

import lombok.Data;

@Data
public class ClickDialCallResponse {
    private Long attemptId;
    private Long assignmentId;
    private String businessCallId;
    private String frontendCallId;
    private String phoneNumber;
    private String status;
}
