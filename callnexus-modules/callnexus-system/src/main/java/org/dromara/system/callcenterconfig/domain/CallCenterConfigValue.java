package org.dromara.system.callcenterconfig.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_callcenter_config_value")
public class CallCenterConfigValue extends TenantEntity {
    @TableId
    private Long id;
    private String configKey;
    private String configValue;
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
