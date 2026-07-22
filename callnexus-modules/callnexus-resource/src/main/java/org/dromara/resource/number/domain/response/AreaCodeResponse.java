package org.dromara.resource.number.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class AreaCodeResponse {

    private Long id;

    private String countryCode;

    private String province;

    private String city;

    private String areaCode;

    private Boolean enabled;

    private Date createTime;

    private Integer version;
}
