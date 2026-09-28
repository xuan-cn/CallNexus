package org.dromara.resource.number.service.impl;

import org.dromara.resource.number.domain.AreaCode;
import org.dromara.resource.number.domain.request.PhoneNumberNormalizeRequest;
import org.dromara.resource.number.mapper.AreaCodeMapper;
import org.dromara.resource.number.mapper.MobileNumberSegmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class PhoneNumberNormalizationServiceImplTest {

    private PhoneNumberNormalizationServiceImpl service;

    @BeforeEach
    void setUp() {
        AreaCodeMapper areaCodeMapper = mock(AreaCodeMapper.class);
        MobileNumberSegmentMapper mobileNumberSegmentMapper = mock(MobileNumberSegmentMapper.class);
        AreaCode areaCode = new AreaCode();
        areaCode.setAreaCode("0451");
        areaCode.setCountryCode("86");
        areaCode.setProvince("黑龙江");
        areaCode.setCity("哈尔滨");
        when(areaCodeMapper.selectList(any())).thenReturn(List.of(areaCode));
        service = new PhoneNumberNormalizationServiceImpl(areaCodeMapper, mobileNumberSegmentMapper);
    }

    @Test
    void keepsMissingAreaCodeZeroWhenRuleDisabled() {
        PhoneNumberNormalizeRequest request = request("45188886666");
        request.setAddMissingAreaCodeZero(false);

        assertEquals("45188886666", service.normalizeInternal(request).getDialNumber());
    }

    @Test
    void addsMissingAreaCodeZeroWhenRuleEnabled() {
        PhoneNumberNormalizeRequest request = request("45188886666");
        request.setAddMissingAreaCodeZero(true);

        assertEquals("045188886666", service.normalizeInternal(request).getDialNumber());
        assertEquals("LANDLINE_ADD_ZERO_PREFIX", service.normalizeInternal(request).getReason());
    }

    @Test
    void appliesLocalAreaCodeCountryCodeAndOutboundPrefixInOrder() {
        PhoneNumberNormalizeRequest request = request("+8688886666");
        request.setLocalAreaCode("0451");
        request.setAddLocalAreaCode(true);
        request.setStripChinaCountryCode(true);
        request.setOutboundPrefix("9");

        assertEquals("9045188886666", service.normalizeInternal(request).getDialNumber());
    }

    private PhoneNumberNormalizeRequest request(String rawNumber) {
        PhoneNumberNormalizeRequest request = new PhoneNumberNormalizeRequest();
        request.setRawNumber(rawNumber);
        request.setAddLocalAreaCode(false);
        request.setAddMissingAreaCodeZero(false);
        request.setStripChinaCountryCode(false);
        return request;
    }
}
