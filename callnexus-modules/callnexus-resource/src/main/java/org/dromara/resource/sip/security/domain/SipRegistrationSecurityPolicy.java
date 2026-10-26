package org.dromara.resource.sip.security.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_sip_registration_security_policy")
public class SipRegistrationSecurityPolicy extends TenantEntity {
    @TableId
    private Long id;
    private Long nodeId;
    private Boolean enabled;
    private Integer pairWindowSeconds;
    private Integer pairFailureThreshold;
    private Integer accountWindowSeconds;
    private Integer accountFailureThreshold;
    private Integer ipWindowSeconds;
    private Integer ipFailureThreshold;
    private Integer firstBlockSeconds;
    private Integer secondBlockSeconds;
    private Integer repeatedBlockSeconds;
    private Boolean alertEnabled;
    @Version
    private Integer version;
}
