package org.dromara.ai.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class AiTtsProviderResponse {
    private Long id;
    private String providerCode;
    private String providerName;
    private String providerType;
    private String endpointUrl;
    private String httpMethod;
    private String authType;
    private String authHeaderName;
    private String defaultVoice;
    private String defaultFormat;
    private Integer defaultSampleRate;
    private Integer timeoutSeconds;
    private Boolean enabled;
    private String remark;
    private Integer version;
    private Date createTime;
}
