package org.dromara.customer.customer.domain.request;

import lombok.Data;

import java.util.List;

@Data
public class CustomerAssignmentRequest {
    private List<Long> customerIds;
    private Boolean selectAll;
    private CustomerPageQuery selectionQuery;
    private String customerType;
    private String sourceChannel;
    private String tags;
    private Long skillGroupId;
    private Long agentId;
    private List<Long> agentIds;
    private String allocationMode;
    private String remark;
}
