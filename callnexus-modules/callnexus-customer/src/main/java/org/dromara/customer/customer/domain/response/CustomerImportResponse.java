package org.dromara.customer.customer.domain.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CustomerImportResponse {
    private int totalCount;
    private int importedCount;
    private int skippedCount;
    private int failedCount;
    private List<Row> rows = new ArrayList<>();

    @Data
    public static class Row {
        private int rowNumber;
        private String customerName;
        private String primaryPhone;
        private String status;
        private String message;
        private Long customerId;
    }
}
