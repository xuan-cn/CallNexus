package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiSpeechTemplateRequest {
    private Long id;
    @NotBlank private String templateCode;
    @NotBlank private String templateName;
    @NotBlank private String businessType;
    @NotBlank private String templateText;
    private String defaultVoice;
    private Boolean enabled;
    private String remark;
    private Integer version;
}
