package org.dromara.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_openapi_route_grant")
public class OpenApiRouteGrant extends TenantEntity {
    @TableId
    private Long id;
    private Long applicationId;
    private String routePolicyCode;
    private Boolean enabled;
    @Version
    private Integer version;
}
