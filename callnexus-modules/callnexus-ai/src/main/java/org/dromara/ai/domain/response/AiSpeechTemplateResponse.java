package org.dromara.ai.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class AiSpeechTemplateResponse {
    private Long id;
    private String templateCode;
    private String templateName;
    private String businessType;
    private String templateText;
    private String defaultVoice;
    private Boolean enabled;
    private String remark;
    private Integer version;
    private Date createTime;
}
