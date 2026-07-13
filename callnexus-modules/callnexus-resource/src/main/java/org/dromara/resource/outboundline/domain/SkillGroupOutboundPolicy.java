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
@TableName("cc_skill_group_outbound_policy")
public class SkillGroupOutboundPolicy extends TenantEntity {
    @TableId
    private Long id;
    private Long nodeId;
    private Long skillGroupId;
    private Long outboundLinePolicyId;
    private Boolean enabled;
    private String remark;
    @TableLogic
    private Boolean deleted;
    @Version
    private Integer version;
}
