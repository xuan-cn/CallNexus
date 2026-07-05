package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_model_provider")
public class AiModelProvider extends TenantEntity {
    @TableId private Long id;
    private String providerCode;
    private String providerName;
    private String providerType;
    private String baseUrl;
    @EncryptField private String apiKey;
    private String organizationId;
    private Integer connectTimeoutSeconds;
    private Integer readTimeoutSeconds;
    private String extraConfigJson;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
