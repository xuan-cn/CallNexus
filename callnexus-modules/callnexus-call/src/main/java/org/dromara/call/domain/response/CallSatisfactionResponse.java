package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallSatisfactionResponse {
    private Integer score;
    private String digit;
    private String status;
    private LocalDateTime submittedAt;
}
