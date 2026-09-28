package org.dromara.customer.ticket.domain.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

@Data
public class UpdateTicketDeadlineRequest {
    @NotNull(message = "请选择是否启用办结时限")
    private Boolean enabled;

    private Date dueAt;

    @Min(value = 0, message = "提前提醒时间不能小于0分钟")
    @Max(value = 525600, message = "提前提醒时间不能超过一年")
    private Integer remindBeforeMinutes;
}
