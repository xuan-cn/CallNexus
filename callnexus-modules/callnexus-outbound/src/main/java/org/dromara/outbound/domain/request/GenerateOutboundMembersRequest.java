package org.dromara.outbound.domain.request;

import jakarta.validation.Valid;
import lombok.Data;
import org.dromara.customer.customer.domain.request.CustomerPageQuery;

@Data
public class GenerateOutboundMembersRequest {
    @Valid
    private CustomerPageQuery selectionQuery;
}
