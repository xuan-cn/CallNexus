package org.dromara.customer.customer.domain.request;

import lombok.Data;

@Data
public class CustomerImportTaskQuery {
    private String taskName;
    private String status;
}
