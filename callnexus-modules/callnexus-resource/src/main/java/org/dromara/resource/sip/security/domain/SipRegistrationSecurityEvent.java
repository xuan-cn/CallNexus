package org.dromara.resource.sip.security.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_sip_registration_security_event")
public class SipRegistrationSecurityEvent extends TenantEntity {
    @TableId
    private Long id;
    private String eventKey;
    private Long nodeId;
    private Long sipAccountId;
    private String extensionSnapshot;
    private String authUsernameSnapshot;
    private String sourceIp;
    private Integer sourcePort;
    private String transport;
    private String userAgent;
    private String result;
    private String reasonCode;
    private String riskLevel;
    private LocalDateTime occurredAt;
}
