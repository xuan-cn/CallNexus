package org.dromara.resource.voicemail.domain.request;

import lombok.Data;

@Data
public class VoiceMailBoxPageQuery {
    private String boxCode;
    private String boxName;
    private Boolean enabled;
}
