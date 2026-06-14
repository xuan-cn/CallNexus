package org.dromara.outbound.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CompleteOutboundMemberRequest {
    @NotBlank
    @Pattern(regexp = "^(CONNECTED|NO_ANSWER|BUSY|INVALID_NUMBER|NOT_INTERESTED|FOLLOW_UP|OTHER)$",
        message = "请选择合法的外呼结果")
    private String resultCode;
    @Size(max = 1000)
    private String resultRemark;
    private LocalDateTime nextFollowUpAt;
    private Boolean retry;
}
