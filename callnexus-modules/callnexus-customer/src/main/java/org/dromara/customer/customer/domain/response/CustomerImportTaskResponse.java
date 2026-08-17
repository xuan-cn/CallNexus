package org.dromara.customer.customer.domain.response;

import lombok.Data;
import java.util.Date;

@Data
public class CustomerImportTaskResponse {
    private Long id;
    private String taskCode;
    private String taskName;
    private String description;
    private String status;
    private String duplicateStrategy;
    private Long formTemplateId;
    private String fieldMappingJson;
    private String defaultCustomerType;
    private String defaultSourceChannel;
    private String defaultTags;
    private String defaultRemark;
    private long batchCount;
    private long importedCount;
    private long failedCount;
    private long assignedCount;
    private long unassignedCount;
    private Date lastImportTime;
    private Date createTime;
}
