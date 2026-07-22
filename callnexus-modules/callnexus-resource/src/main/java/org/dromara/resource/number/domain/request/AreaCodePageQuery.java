package org.dromara.resource.number.domain.request;

import lombok.Data;

@Data
public class AreaCodePageQuery {

    private String countryCode;

    private String province;

    private String city;

    private String areaCode;

    private Boolean enabled;
}
