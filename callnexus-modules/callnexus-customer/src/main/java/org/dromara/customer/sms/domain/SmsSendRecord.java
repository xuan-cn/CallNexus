package org.dromara.customer.sms.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_sms_send_record")
public class SmsSendRecord extends TenantEntity {
    @TableId private Long id;
    private Long customerId;
    private Long channelId;
    private Long templateId;
    private String channelName;
    private String templateName;
    private String supplier;
    private String signature;
    private String providerTemplateId;
    private String phone;
    private String content;
    private String parametersJson;
    private String source;
    private String operatorName;
    private String requestId;
    private String requestHash;
    private String status;
    private String resultCode;
    private String providerReference;
    private LocalDateTime processingAt;
    private LocalDateTime submittedAt;
}
