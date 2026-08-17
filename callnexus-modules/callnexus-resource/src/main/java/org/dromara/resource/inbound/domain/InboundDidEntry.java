package org.dromara.resource.inbound.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_inbound_did_entry")
public class InboundDidEntry extends TenantEntity {
    @TableId
    private Long id;
    private Long nodeId;
    private Long gatewayId;
    private String entryName;
    private String entryType;
    private String didNumber;
    private String portCode;
    private String accountCode;
    private String headerName;
    private String headerValue;
    private String routeTargetType;
    private String routeTargetId;
    private Integer priority;
    private Boolean enabled;
    private String remark;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
