package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_outbound_blacklist_import_batch")
public class OutboundBlacklistImportBatch extends TenantEntity {
    @TableId private Long id;
    private String scopeType;
    private Long taskId;
    private String fileName;
    private String status;
    private Integer totalCount;
    private Integer validCount;
    private Integer invalidCount;
    private Integer duplicateCount;
    private Integer importedCount;
    @TableLogic private Boolean deleted;
}
