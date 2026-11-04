package org.dromara.customer.sms.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_sms_template")
public class SmsTemplate extends TenantEntity {
    @TableId private Long id;
    private Long channelId;
    private String templateName;
    private String providerTemplateId;
    private String content;
    private String variablesJson;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
