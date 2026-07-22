package org.dromara.resource.number.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AreaCodeRequest {

    @Size(max = 8, message = "国家码长度不能超过8位")
    private String countryCode;

    @NotBlank(message = "省份不能为空")
    @Size(max = 64, message = "省份长度不能超过64位")
    private String province;

    @NotBlank(message = "城市不能为空")
    @Size(max = 64, message = "城市长度不能超过64位")
    private String city;

    @NotBlank(message = "区号不能为空")
    @Size(max = 16, message = "区号长度不能超过16位")
    private String areaCode;

    private Boolean enabled;

    private Integer version;
}
