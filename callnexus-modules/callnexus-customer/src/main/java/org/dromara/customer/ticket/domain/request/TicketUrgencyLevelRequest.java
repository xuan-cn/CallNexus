package org.dromara.customer.ticket.domain.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TicketUrgencyLevelRequest {
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_.-]{1,32}$")
    private String levelCode;
    @NotBlank
    @Size(max = 32)
    private String levelName;
    @NotNull
    @Min(0)
    @Max(10000)
    private Integer priorityWeight;
    @NotBlank
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private String tagColor;
    @NotNull
    private Boolean isDefault;
    @NotNull
    private Boolean enabled;
    @NotNull
    @Min(0)
    @Max(10000)
    private Integer sortOrder;
    @Size(max = 255)
    private String remark;
    private Integer version;
}
