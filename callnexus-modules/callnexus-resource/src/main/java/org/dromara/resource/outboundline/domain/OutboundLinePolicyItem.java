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
@TableName("cc_outbound_line_policy_item")
public class OutboundLinePolicyItem extends TenantEntity {
    @TableId
    private Long id;
    private Long policyId;
    private Long phoneNumberId;
    private Integer weight;
    private Integer sortOrder;
    private Boolean enabled;
    @TableLogic
    private Boolean deleted;
    @Version
    private Integer version;
}
