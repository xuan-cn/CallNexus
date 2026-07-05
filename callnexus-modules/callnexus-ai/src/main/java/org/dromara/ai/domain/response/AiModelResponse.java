package org.dromara.ai.domain.response;

import lombok.Data;
import java.util.Date;

@Data
public class AiModelResponse {
    private Long id;
    private Long providerId;
    private String providerName;
    private String modelCode;
    private String modelName;
    private String capability;
    private Integer vectorDimension;
    private Integer maxBatchSize;
    private Integer maxInputTokens;
    private Boolean defaultModel;
    private String requestOptionsJson;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
