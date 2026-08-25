package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_outbound_task_call_window")
public class OutboundTaskCallWindow extends TenantEntity {
    @TableId
    private Long id;
    private Long taskId;
    private String weekdays;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer sortOrder;
    private Boolean enabled;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
