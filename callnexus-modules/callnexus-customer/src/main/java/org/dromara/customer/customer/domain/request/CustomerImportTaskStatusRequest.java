package org.dromara.customer.customer.domain.request;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CustomerImportTaskStatusRequest {
    @Pattern(regexp = "ENABLED|DISABLED", message = "任务状态不正确")
    private String status;
}
