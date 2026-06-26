package org.dromara.resource.outboundline.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_outbound_line_policy")
public class OutboundLinePolicy extends TenantEntity {
    @TableId
    private Long id;
    private Long nodeId;
    private String policyCode;
    private String policyName;
    private String policyType;
    private Boolean defaultPolicy;
    private Boolean enabled;
    private String remark;
    @TableLogic
    private Boolean deleted;
    @Version
    private Integer version;
}
