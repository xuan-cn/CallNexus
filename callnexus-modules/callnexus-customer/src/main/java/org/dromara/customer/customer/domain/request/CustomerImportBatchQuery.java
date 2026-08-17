package org.dromara.customer.customer.domain.request;

import lombok.Data;

@Data
public class CustomerImportBatchQuery {
    private Long taskId;
    private String fileName;
    private String status;
}
