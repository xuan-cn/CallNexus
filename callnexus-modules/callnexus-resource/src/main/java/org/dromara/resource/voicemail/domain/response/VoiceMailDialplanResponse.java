package org.dromara.resource.voicemail.domain.response;

import lombok.Data;

@Data
public class VoiceMailDialplanResponse {
    private Long id;
    private String boxCode;
    private String boxName;
    private Long promptMediaId;
    private String promptPath;
    private Integer maxSeconds;
    private Integer silenceThreshold;
    private Integer silenceHits;
}
