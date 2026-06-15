package org.dromara.outbound.domain.response;

import lombok.Data;

import java.util.List;

@Data
public class OutboundImportBatchResponse {
    private Long id;
    private Long taskId;
    private String fileName;
    private String status;
    private Integer totalCount;
    private Integer validCount;
    private Integer invalidCount;
    private Integer duplicateCount;
    private Integer blacklistedCount;
    private Integer importedCount;
    private List<OutboundImportRowResponse> rows;
}
