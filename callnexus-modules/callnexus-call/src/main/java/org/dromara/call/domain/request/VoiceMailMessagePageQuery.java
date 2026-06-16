package org.dromara.call.domain.request;

import lombok.Data;

@Data
public class VoiceMailMessagePageQuery {
    private Long voicemailBoxId;
    private String callerNumber;
    private String calledNumber;
    private String status;
}
