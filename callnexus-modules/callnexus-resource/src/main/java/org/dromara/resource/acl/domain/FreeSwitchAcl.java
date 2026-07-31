package org.dromara.resource.acl.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_freeswitch_acl")
public class FreeSwitchAcl extends TenantEntity {
    @TableId
    private Long id;
    private Long nodeId;
    private String aclCode;
    private String aclName;
    private String purpose;
    private String defaultAction;
    private String entriesJson;
    private Boolean enabled;
    private Long publishedVersionId;
    private Integer publishedVersionNo;
    private String syncStatus;
    private String syncError;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
