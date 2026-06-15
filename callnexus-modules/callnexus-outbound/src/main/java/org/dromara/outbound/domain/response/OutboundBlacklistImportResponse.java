package org.dromara.outbound.domain.response;

import lombok.Data;

import java.util.List;

@Data
public class OutboundBlacklistImportResponse {
    private Long id;
    private String scopeType;
    private Long taskId;
    private String fileName;
    private String status;
    private Integer totalCount;
    private Integer validCount;
    private Integer invalidCount;
    private Integer duplicateCount;
    private Integer importedCount;
    private List<Row> rows;

    @Data
    public static class Row {
        private Long id;
        private Integer rowNumber;
        private String originalPhone;
        private String normalizedPhone;
        private String reason;
        private String status;
        private String errorMessage;
    }
}
