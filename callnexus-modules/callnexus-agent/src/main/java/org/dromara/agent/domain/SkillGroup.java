package org.dromara.agent.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_skill_group")
public class SkillGroup extends TenantEntity {
    @TableId private Long id;
    private String groupCode;
    private String groupName;
    private Boolean enabled;
    private String remark;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
