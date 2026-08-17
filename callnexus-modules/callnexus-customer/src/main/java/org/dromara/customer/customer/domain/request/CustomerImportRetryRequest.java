package org.dromara.customer.customer.domain.request;

import lombok.Data;

import java.util.List;

@Data
public class CustomerImportRetryRequest {
    private List<Long> rowIds;
}
