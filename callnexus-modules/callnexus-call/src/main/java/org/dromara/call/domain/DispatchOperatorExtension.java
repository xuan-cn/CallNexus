package org.dromara.call.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_dispatch_operator_extension")
public class DispatchOperatorExtension extends TenantEntity {
    @TableId
    private Long id;
    private Long userId;
    private Long sipAccountId;
}
