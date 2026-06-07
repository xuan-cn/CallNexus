package org.dromara.customer.customer.domain.request;

import lombok.Data;

@Data
public class CustomerPageQuery {
    private String primaryPhone;
    private String customerName;
}
