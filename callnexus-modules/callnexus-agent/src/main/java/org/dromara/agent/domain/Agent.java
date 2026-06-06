package org.dromara.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_agent")
public class Agent extends TenantEntity {
    @TableId
    private Long id;
    private String agentCode;
    private String agentName;
    private Long userId;
    private Boolean enabled;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
