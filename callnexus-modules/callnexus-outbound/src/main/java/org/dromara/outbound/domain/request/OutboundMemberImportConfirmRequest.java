package org.dromara.outbound.domain.request;

import lombok.Data;

@Data
public class OutboundMemberImportConfirmRequest {
    private Boolean autoCreateCustomer = true;
}
