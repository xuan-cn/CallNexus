package org.dromara.resource.number.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PhoneNumberNormalizeRequest {

    @NotBlank(message = "号码不能为空")
    private String rawNumber;

    private String usage;

    private String localAreaCode;

    private Boolean addLocalAreaCode;

    private Boolean stripChinaCountryCode;

    private String outboundPrefix;
}
