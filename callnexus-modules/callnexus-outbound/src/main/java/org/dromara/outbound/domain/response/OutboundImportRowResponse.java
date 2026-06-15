package org.dromara.outbound.domain.response;

import lombok.Data;

@Data
public class OutboundImportRowResponse {
    private Long id;
    private Integer rowNumber;
    private String customerName;
    private String originalPhone;
    private String normalizedPhone;
    private String status;
    private String errorMessage;
    private Long customerId;
}
