package org.dromara.customer.customer.domain.request;

import lombok.Data;
import org.dromara.customer.form.domain.request.DynamicFieldFilter;

import java.util.List;

@Data
public class CustomerPageQuery {
    private Integer pageNum = 1;
    private Integer pageSize = 10;
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
    private Long templateId;
    private List<DynamicFieldFilter> dynamicFilters;
}
