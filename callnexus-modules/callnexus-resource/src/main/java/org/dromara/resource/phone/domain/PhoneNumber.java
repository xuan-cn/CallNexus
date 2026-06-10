package org.dromara.resource.phone.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_phone_number")
public class PhoneNumber extends TenantEntity {
    @TableId
    private Long id;
    private String number;
    private String numberName;
    private String numberType;
    private Long nodeId;
    private Long gatewayId;
    private String routeType;
    private String routeTarget;
    private Boolean outboundDefault;
    private Boolean enabled;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
