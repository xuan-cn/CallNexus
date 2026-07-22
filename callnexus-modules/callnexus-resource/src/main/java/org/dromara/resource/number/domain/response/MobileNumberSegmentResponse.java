package org.dromara.resource.number.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class MobileNumberSegmentResponse {

    private Long id;

    private String countryCode;

    private String segmentPrefix;

    private String province;

    private String city;

    private String carrier;

    private Boolean enabled;

    private Date createTime;

    private Integer version;
}
