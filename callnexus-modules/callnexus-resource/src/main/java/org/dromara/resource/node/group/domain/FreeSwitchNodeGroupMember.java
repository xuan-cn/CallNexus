package org.dromara.resource.node.group.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_freeswitch_node_group_member")
public class FreeSwitchNodeGroupMember extends TenantEntity {
    @TableId private Long id;
    private Long groupId;
    private Long nodeId;
}
