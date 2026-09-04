package org.dromara.ai.domain.response;

import lombok.Data;

@Data
public class AiWorkflowCustomerContext {
    private String phone;
    private Long customerId;
    private String customerName;
    private Long templateId;
    private String formData;
    private Long outboundTaskId;
    private String outboundTaskName;
    private Long outboundMemberId;
    private Integer outboundAttemptCount;
}
