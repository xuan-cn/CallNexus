package org.dromara.customer.customer.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_customer_import_task")
public class CustomerImportTask extends TenantEntity {
    @TableId
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
    @TableLogic
    private Boolean deleted;
}
