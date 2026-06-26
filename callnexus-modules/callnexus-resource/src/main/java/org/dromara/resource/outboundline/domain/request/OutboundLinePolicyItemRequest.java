package org.dromara.resource.outboundline.domain.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OutboundLinePolicyItemRequest {
    private Long id;
    @NotNull
    private Long phoneNumberId;
    @Min(1)
    private Integer weight = 1;
    @Min(0)
    private Integer sortOrder = 0;
    @NotNull
    private Boolean enabled = true;
    private Integer version;
}
