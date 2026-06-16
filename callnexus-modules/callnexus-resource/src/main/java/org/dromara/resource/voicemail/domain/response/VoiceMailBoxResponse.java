package org.dromara.resource.voicemail.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class VoiceMailBoxResponse {
    private Long id;
    private String boxCode;
    private String boxName;
    private Long promptMediaId;
    private String promptMediaName;
    private Integer maxSeconds;
    private Integer silenceThreshold;
    private Integer silenceHits;
    private Boolean enabled;
    private String remark;
    private Integer version;
    private Date createTime;
}
