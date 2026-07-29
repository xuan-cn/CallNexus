package org.dromara.customer.customer.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class CustomerResponse {
    private Long id;
    private String primaryPhone;
    private String customerName;
    private Long templateId;
    private String sourceCallId;
    private LocalDateTime createTime;
    private List<CustomerPhoneResponse> phones;
    private Map<String, Object> formData;
}
