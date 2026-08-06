package org.dromara.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_openapi_application_scope")
public class OpenApiApplicationScope extends TenantEntity {
    @TableId
    private Long id;
    private Long applicationId;
    private String scopeCode;
}
