package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_outbound_task_retry_rule")
public class OutboundTaskRetryRule extends TenantEntity {
    @TableId
    private Long id;
    private Long taskId;
    private String resultCode;
    private Boolean retryEnabled;
    private Integer maxRetryCount;
    private Integer retryIntervalMinutes;
    private Integer sortOrder;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
