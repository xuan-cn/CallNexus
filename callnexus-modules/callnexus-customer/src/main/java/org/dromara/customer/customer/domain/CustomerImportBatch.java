package org.dromara.customer.customer.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_customer_import_batch")
public class CustomerImportBatch extends TenantEntity {
    @TableId
    private Long id;
    private Long taskId;
    private String fileName;
    private String status;
    private String duplicateStrategy;
    private String defaultCustomerType;
    private String defaultSourceChannel;
    private String defaultTags;
    private String defaultRemark;
    private Long formTemplateId;
    private String fieldMappingJson;
    private Integer totalCount;
    private Integer importedCount;
    private Integer skippedCount;
    private Integer failedCount;
    private String failureReason;
    @TableLogic
    private Boolean deleted;
}
