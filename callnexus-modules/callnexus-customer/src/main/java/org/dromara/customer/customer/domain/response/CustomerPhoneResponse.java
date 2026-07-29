package org.dromara.customer.customer.domain.response;

import lombok.Data;

@Data
public class CustomerPhoneResponse {
    private Long id;
    private String phoneNumber;
    private String normalizedPhone;
    private String phoneType;
    private String phoneLabel;
    private Boolean primaryFlag;
    private Boolean enabled;
    private Integer sortOrder;
}
