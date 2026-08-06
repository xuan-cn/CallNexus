package org.dromara.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_openapi_credential")
public class OpenApiCredential extends TenantEntity {
    @TableId
    private Long id;
    private Long applicationId;
    private String credentialName;
    private String clientId;
    private String secretHash;
    private String secretHint;
    private String status;
    private Date expiresAt;
    private Date lastUsedAt;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
