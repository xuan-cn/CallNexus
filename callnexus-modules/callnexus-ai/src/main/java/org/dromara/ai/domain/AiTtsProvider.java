package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_tts_provider")
public class AiTtsProvider extends TenantEntity {
    @TableId
    private Long id;
    private String providerCode;
    private String providerName;
    private String providerType;
    private String endpointUrl;
    private String httpMethod;
    private String authType;
    private String authHeaderName;
    private String authToken;
    private String defaultVoice;
    private String defaultFormat;
    private Integer defaultSampleRate;
    private Integer timeoutSeconds;
    private Boolean enabled;
    private String remark;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
