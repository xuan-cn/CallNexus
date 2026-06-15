package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_outbound_blacklist_import_row")
public class OutboundBlacklistImportRow extends TenantEntity {
    @TableId private Long id;
    private Long batchId;
    @TableField("source_row_number")
    private Integer rowNumber;
    private String originalPhone;
    private String normalizedPhone;
    private String reason;
    private String status;
    private String errorMessage;
    @TableLogic private Boolean deleted;
}
