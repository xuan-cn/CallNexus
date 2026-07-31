package org.dromara.resource.acl.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_freeswitch_acl_version")
public class FreeSwitchAclVersion extends TenantEntity {
    @TableId
    private Long id;
    private Long aclId;
    private Long nodeId;
    private Integer versionNo;
    private String snapshotJson;
    private Boolean currentVersion;
    private LocalDateTime publishedAt;
}
