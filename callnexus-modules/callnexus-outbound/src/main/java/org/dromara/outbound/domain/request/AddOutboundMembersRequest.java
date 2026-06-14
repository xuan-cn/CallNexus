package org.dromara.outbound.domain.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class AddOutboundMembersRequest {
    @NotEmpty(message = "请至少选择一个客户")
    private List<Long> customerIds;
}
