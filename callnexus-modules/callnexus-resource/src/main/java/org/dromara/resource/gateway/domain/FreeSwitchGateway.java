package org.dromara.resource.gateway.domain;

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
@TableName("cc_freeswitch_gateway")
public class FreeSwitchGateway extends TenantEntity {
    @TableId
    private Long id;
    private Long nodeId;
    private String gatewayCode;
    private String gatewayName;
    private String direction;
    private String proxy;
    private String realm;
    private String username;
    @EncryptField
    private String password;
    private Boolean registerEnabled;
    private String transport;
    private String callerIdNumber;
    private Integer ping;
    private Integer expireSeconds;
    private Integer retrySeconds;
    private Integer pingMax;
    private Integer pingMin;
    private Boolean callerIdInFrom;
    private String fromUser;
    private String fromDomain;
    private String contactParams;
    private String dialplanContext;
    private String extension;
    private String description;
    private Boolean enabled;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
