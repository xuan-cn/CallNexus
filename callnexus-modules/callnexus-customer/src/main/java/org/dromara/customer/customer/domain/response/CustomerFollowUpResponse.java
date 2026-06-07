package org.dromara.customer.customer.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CustomerFollowUpResponse {
    private Long id;
    private String content;
    private Long followUpBy;
    private String followUpByName;
    private LocalDateTime followUpTime;
}
