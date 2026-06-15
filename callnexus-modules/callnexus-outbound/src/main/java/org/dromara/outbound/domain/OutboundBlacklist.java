package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_outbound_blacklist")
public class OutboundBlacklist extends TenantEntity {
    @TableId private Long id;
    private String scopeType;
    private Long taskId;
    private String originalPhone;
    private String normalizedPhone;
    private String reason;
    private String source;
    private LocalDateTime effectiveAt;
    private LocalDateTime expiresAt;
    private Boolean enabled;
    @TableLogic private Boolean deleted;
}
