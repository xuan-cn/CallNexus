package org.dromara.resource.number.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MobileNumberSegmentRequest {

    @Size(max = 8, message = "国家码长度不能超过8位")
    private String countryCode;

    @NotBlank(message = "号段不能为空")
    @Size(max = 16, message = "号段长度不能超过16位")
    private String segmentPrefix;

    @NotBlank(message = "省份不能为空")
    @Size(max = 64, message = "省份长度不能超过64位")
    private String province;

    @NotBlank(message = "城市不能为空")
    @Size(max = 64, message = "城市长度不能超过64位")
    private String city;

    @NotBlank(message = "运营商不能为空")
    @Size(max = 32, message = "运营商长度不能超过32位")
    private String carrier;

    private Boolean enabled;

    private Integer version;
}
