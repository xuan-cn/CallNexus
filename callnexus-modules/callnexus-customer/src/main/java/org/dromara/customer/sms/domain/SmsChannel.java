package org.dromara.customer.sms.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_sms_channel")
public class SmsChannel extends TenantEntity {
    @TableId private Long id;
    private String channelName;
    private String supplier;
    @EncryptField @ToString.Exclude private String accessKeyId;
    @EncryptField @ToString.Exclude private String accessKeySecret;
    private String sdkAppId;
    private String signature;
    private Boolean enabled;
    private Integer minuteLimit;
    private Integer dailyLimit;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
