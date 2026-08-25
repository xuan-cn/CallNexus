package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_outbound_task_source")
public class OutboundTaskSource extends TenantEntity {
    @TableId
    private Long id;
    private Long taskId;
    private Long importTaskId;
    private Long importBatchId;
    private String customerType;
    private String tags;
    private Long skillGroupId;
    private Long agentId;
    private String assignmentState;
    private String phoneStrategy;
    private String phoneLabel;
    private Boolean enabled;
    private String filterSummary;
    @TableLogic
    private Boolean deleted;
}
