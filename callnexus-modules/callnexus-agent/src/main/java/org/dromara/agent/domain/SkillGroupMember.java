package org.dromara.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_skill_group_member")
public class SkillGroupMember extends TenantEntity {
    @TableId private Long id;
    private Long skillGroupId;
    private Long agentId;
    private Integer skillLevel;
    private Integer priority;
    @TableLogic private Boolean deleted;
}
