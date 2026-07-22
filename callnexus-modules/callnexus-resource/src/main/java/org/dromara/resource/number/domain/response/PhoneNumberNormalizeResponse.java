package org.dromara.resource.number.domain.response;

import lombok.Data;

@Data
public class PhoneNumberNormalizeResponse {

    private String rawNumber;
    private String cleanedNumber;
    private String normalizedNumber;
    private String dialNumber;
    private String numberType;
    private String countryCode;
    private String areaCode;
    private String mobileSegment;
    private String province;
    private String city;
    private String carrier;
    private Boolean changed;
    private String reason;
}
