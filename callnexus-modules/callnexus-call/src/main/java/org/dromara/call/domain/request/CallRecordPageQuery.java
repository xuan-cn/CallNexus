package org.dromara.call.domain.request;

import lombok.Data;

@Data
public class CallRecordPageQuery {
    private Long customerId;
    private Long ticketId;
    private String participantNumber;
    private String callerNumber;
    private String calledNumber;
    private String direction;
    private String callStatus;
    private String hangupCause;
}
