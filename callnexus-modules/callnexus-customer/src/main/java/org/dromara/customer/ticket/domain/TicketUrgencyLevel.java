package org.dromara.customer.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ticket_urgency_level")
public class TicketUrgencyLevel extends TenantEntity {
    @TableId
    private Long id;
    private String levelCode;
    private String levelName;
    private Integer priorityWeight;
    private String tagColor;
    private Boolean isDefault;
    private Boolean enabled;
    private Integer sortOrder;
    private String remark;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
