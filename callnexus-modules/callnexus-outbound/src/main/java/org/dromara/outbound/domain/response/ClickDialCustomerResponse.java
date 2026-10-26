package org.dromara.outbound.domain.response;

import lombok.Data;
import org.dromara.customer.customer.domain.response.CustomerPhoneResponse;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ClickDialCustomerResponse {
    private Long assignmentId;
    private Long customerId;
    private String customerName;
    private String primaryPhone;
    private Long templateId;
    private String customerType;
    private String sourceChannel;
    private String tags;
    private Long skillGroupId;
    private String assignmentRemark;
    private LocalDateTime assignedAt;
    private String dialStatus;
    private Integer attemptCount;
    private LocalDateTime lastCalledAt;
    private String lastBusinessCallId;
    private String lastResultCode;
    private String lastResultRemark;
    private LocalDateTime nextFollowUpAt;
    private LocalDateTime completedAt;
    private List<CustomerPhoneResponse> phones;
}
