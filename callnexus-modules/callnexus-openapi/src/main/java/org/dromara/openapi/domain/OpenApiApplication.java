package org.dromara.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import org.dromara.common.encrypt.annotation.EncryptField;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_openapi_application")
public class OpenApiApplication extends TenantEntity {
    @TableId
    private Long id;
    private String appCode;
    private String appName;
    private Boolean enabled;
    private Integer tokenTtlSeconds;
    private Integer requestsPerMinute;
    private Integer maxConcurrentCalls;
    private Boolean websocketEnabled;
    private Boolean webhookEnabled;
    private String webhookUrl;
    @EncryptField
    private String webhookSecret;
    private String subscribedEvents;
    private String description;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
