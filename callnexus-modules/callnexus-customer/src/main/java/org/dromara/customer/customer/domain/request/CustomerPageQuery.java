package org.dromara.customer.customer.domain.request;

import lombok.Data;

@Data
public class CustomerPageQuery {
    private String primaryPhone;
    private String customerName;
    private String customerType;
    private String sourceChannel;
    private String tags;
    private Long skillGroupId;
    private Long agentId;
    private Long importBatchId;
    private Long importTaskId;
    private String assignmentState;
}
