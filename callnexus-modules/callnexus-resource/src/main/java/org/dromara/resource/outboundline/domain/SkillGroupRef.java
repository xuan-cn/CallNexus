package org.dromara.resource.outboundline.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_skill_group")
public class SkillGroupRef extends TenantEntity {
    @TableId
    private Long id;
    private String groupCode;
    private String groupName;
    private Boolean enabled;
    @TableLogic
    private Boolean deleted;
}
