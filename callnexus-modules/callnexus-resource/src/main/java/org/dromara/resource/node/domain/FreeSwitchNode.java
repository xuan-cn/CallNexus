package org.dromara.resource.node.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_freeswitch_node")
public class FreeSwitchNode extends TenantEntity {
    @TableId
    private Long id;
    private String nodeCode;
    private String nodeName;
    private String sipDomain;
    private String wssUrl;
    private String eslHost;
    private Integer eslPort;
    @EncryptField
    private String eslPassword;
    private Boolean enabled;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
