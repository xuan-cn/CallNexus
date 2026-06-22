package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SendDtmfRequest {

    @NotBlank(message = "DTMF 按键不能为空")
    @Pattern(regexp = "^[0-9A-Da-d*#]{1,32}$", message = "DTMF 按键只能包含 0-9、*、#、A-D，最多 32 位")
    private String digits;
}
