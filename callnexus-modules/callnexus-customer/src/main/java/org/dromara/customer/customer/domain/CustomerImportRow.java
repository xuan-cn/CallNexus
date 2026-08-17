package org.dromara.customer.customer.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_customer_import_row")
public class CustomerImportRow extends TenantEntity {
    @TableId
    private Long id;
    private Long taskId;
    private Long batchId;
    @TableField("source_row_number")
    private Integer rowNumber;
    private String customerName;
    private String originalPhone;
    private String normalizedPhone;
    private String additionalPhones;
    private String customerType;
    private String sourceChannel;
    private String tags;
    private String status;
    private String errorMessage;
    private Long customerId;
    private String rawJson;
    @TableLogic
    private Boolean deleted;
}
