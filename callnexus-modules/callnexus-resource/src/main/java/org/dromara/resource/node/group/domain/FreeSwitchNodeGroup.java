package org.dromara.resource.node.group.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_freeswitch_node_group")
public class FreeSwitchNodeGroup extends TenantEntity {
    @TableId private Long id;
    private String groupCode;
    private String groupName;
    private Boolean enabled;
    private String remark;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
