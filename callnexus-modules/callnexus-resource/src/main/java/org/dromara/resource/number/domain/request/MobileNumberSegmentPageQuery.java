package org.dromara.resource.number.domain.request;

import lombok.Data;

@Data
public class MobileNumberSegmentPageQuery {

    private String countryCode;

    private String segmentPrefix;

    private String province;

    private String city;

    private String carrier;

    private Boolean enabled;
}
