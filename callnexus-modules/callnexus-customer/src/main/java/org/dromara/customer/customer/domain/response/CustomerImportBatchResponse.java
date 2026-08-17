package org.dromara.customer.customer.domain.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CustomerImportBatchResponse {
    private Long batchId;
    private Long taskId;
    private String fileName;
    private String status;
    private String duplicateStrategy;
    private String defaultCustomerType;
    private String defaultSourceChannel;
    private String defaultTags;
    private String defaultRemark;
    private int totalCount;
    private int importedCount;
    private int skippedCount;
    private int failedCount;
    private String failureReason;
    private java.util.Date createTime;
    private List<Row> rows = new ArrayList<>();

    @Data
    public static class Row {
        private Long id;
        private int rowNumber;
        private String customerName;
        private String originalPhone;
        private String normalizedPhone;
        private String customerType;
        private String sourceChannel;
        private String tags;
        private String status;
        private String errorMessage;
        private Long customerId;
    }
}
