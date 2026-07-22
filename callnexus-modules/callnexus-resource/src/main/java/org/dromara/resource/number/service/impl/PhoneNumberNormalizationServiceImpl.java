package org.dromara.resource.number.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.number.domain.AreaCode;
import org.dromara.resource.number.domain.MobileNumberSegment;
import org.dromara.resource.number.domain.request.PhoneNumberNormalizeRequest;
import org.dromara.resource.number.domain.response.PhoneNumberNormalizeResponse;
import org.dromara.resource.number.mapper.AreaCodeMapper;
import org.dromara.resource.number.mapper.MobileNumberSegmentMapper;
import org.dromara.resource.number.service.PhoneNumberNormalizationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PhoneNumberNormalizationServiceImpl implements PhoneNumberNormalizationService {

    private static final String DEFAULT_COUNTRY_CODE = "86";

    private final AreaCodeMapper areaCodeMapper;
    private final MobileNumberSegmentMapper mobileNumberSegmentMapper;

    @Override
    public PhoneNumberNormalizeResponse normalize(String tenantId, PhoneNumberNormalizeRequest request) {
        if (request == null || request.getRawNumber() == null || request.getRawNumber().isBlank()) {
            throw new ServiceException("号码不能为空");
        }
        return TenantHelper.dynamic(tenantId, () -> normalizeInternal(request));
    }

    private PhoneNumberNormalizeResponse normalizeInternal(PhoneNumberNormalizeRequest request) {
        String raw = request.getRawNumber();
        String cleaned = clean(raw);
        if (cleaned.isBlank()) {
            throw new ServiceException("号码不能为空");
        }
        PhoneNumberNormalizeResponse response = baseResponse(raw, cleaned);

        if (cleaned.startsWith("+") && !cleaned.startsWith("+86")) {
            response.setNumberType("INTERNATIONAL");
            response.setNormalizedNumber(cleaned);
            response.setDialNumber(withOutboundPrefix(cleaned, request.getOutboundPrefix()));
            response.setReason("INTERNATIONAL_NUMBER");
            finish(response);
            return response;
        }

        String domestic = toDomesticChinaNumber(cleaned, !Boolean.FALSE.equals(request.getStripChinaCountryCode()));
        AreaCode area = null;
        String normalized = domestic;
        String type;
        String reason;

        if (isMobile(domestic)) {
            type = "MOBILE";
            reason = "MOBILE";
            MobileNumberSegment segment = matchMobileSegment(domestic);
            if (segment != null) {
                response.setMobileSegment(segment.getSegmentPrefix());
                response.setProvince(segment.getProvince());
                response.setCity(segment.getCity());
                response.setCarrier(segment.getCarrier());
                response.setCountryCode(segment.getCountryCode());
            }
        } else if (domestic.startsWith("0")) {
            area = matchAreaCode(domestic);
            type = area == null ? "UNKNOWN" : "LANDLINE";
            reason = area == null ? "UNKNOWN_WITH_ZERO_PREFIX" : "LANDLINE_WITH_AREA_CODE";
        } else if (isLocalLandline(domestic)) {
            type = "LANDLINE";
            reason = "LOCAL_LANDLINE";
            String localAreaCode = normalizeAreaCode(request.getLocalAreaCode());
            if (Boolean.TRUE.equals(request.getAddLocalAreaCode()) && !localAreaCode.isBlank()) {
                normalized = localAreaCode + domestic;
                area = matchAreaCode(normalized);
                reason = "LOCAL_LANDLINE_ADD_AREA_CODE";
            }
        } else {
            area = matchAreaCodeWithoutLeadingZero(domestic);
            if (area != null) {
                normalized = "0" + domestic;
                type = "LANDLINE";
                reason = "LANDLINE_ADD_ZERO_PREFIX";
            } else {
                type = "UNKNOWN";
                reason = "UNKNOWN";
            }
        }

        response.setNumberType(type);
        response.setNormalizedNumber(normalized);
        response.setDialNumber(withOutboundPrefix(normalized, request.getOutboundPrefix()));
        response.setReason(reason);
        if (area != null) {
            response.setCountryCode(area.getCountryCode());
            response.setAreaCode(area.getAreaCode());
            response.setProvince(area.getProvince());
            response.setCity(area.getCity());
        } else {
            response.setCountryCode(DEFAULT_COUNTRY_CODE);
        }
        finish(response);
        return response;
    }

    private PhoneNumberNormalizeResponse baseResponse(String raw, String cleaned) {
        PhoneNumberNormalizeResponse response = new PhoneNumberNormalizeResponse();
        response.setRawNumber(raw);
        response.setCleanedNumber(cleaned);
        return response;
    }

    private void finish(PhoneNumberNormalizeResponse response) {
        response.setChanged(!response.getRawNumber().equals(response.getDialNumber()));
        if (response.getDialNumber() == null || !response.getDialNumber().matches("^\\+?[A-Za-z0-9_#*.@-]{3,64}$")) {
            throw new ServiceException("号码包含非法字符");
        }
    }

    private String clean(String value) {
        return value.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
            .replace("（", "")
            .replace("）", "");
    }

    private String toDomesticChinaNumber(String number, boolean stripCountryCode) {
        if (number.startsWith("+86")) {
            return stripCountryCode ? number.substring(3) : number.substring(1);
        }
        if (number.startsWith("0086")) {
            return stripCountryCode ? number.substring(4) : number.substring(2);
        }
        if (stripCountryCode && number.startsWith("86") && number.length() == 13 && isMobile(number.substring(2))) {
            return number.substring(2);
        }
        return number;
    }

    private boolean isMobile(String number) {
        return number != null && number.matches("^1[3-9]\\d{9}$");
    }

    private boolean isLocalLandline(String number) {
        return number != null && number.matches("^\\d{7,8}$");
    }

    private String normalizeAreaCode(String areaCode) {
        if (areaCode == null || areaCode.isBlank()) {
            return "";
        }
        String cleaned = clean(areaCode);
        return cleaned.startsWith("0") ? cleaned : "0" + cleaned;
    }

    private String withOutboundPrefix(String number, String outboundPrefix) {
        if (outboundPrefix == null || outboundPrefix.isBlank()) {
            return number;
        }
        return clean(outboundPrefix) + number;
    }

    private AreaCode matchAreaCode(String number) {
        List<AreaCode> candidates = areaCodes();
        return candidates.stream()
            .filter(area -> hasLandlineSubscriber(number, area.getAreaCode()))
            .max(Comparator.comparingInt(area -> area.getAreaCode().length()))
            .orElse(null);
    }

    private AreaCode matchAreaCodeWithoutLeadingZero(String number) {
        List<AreaCode> candidates = areaCodes();
        return candidates.stream()
            .filter(area -> {
                String code = area.getAreaCode();
                String withoutZero = code.startsWith("0") ? code.substring(1) : code;
                return hasLandlineSubscriber(number, withoutZero);
            })
            .max(Comparator.comparingInt(area -> area.getAreaCode().length()))
            .orElse(null);
    }

    private boolean hasLandlineSubscriber(String number, String areaCode) {
        if (number == null || areaCode == null || !number.startsWith(areaCode)) {
            return false;
        }
        int subscriberLength = number.length() - areaCode.length();
        return subscriberLength == 7 || subscriberLength == 8;
    }

    private List<AreaCode> areaCodes() {
        return areaCodeMapper.selectList(new LambdaQueryWrapper<AreaCode>()
            .eq(AreaCode::getEnabled, true)
            .orderByDesc(AreaCode::getAreaCode));
    }

    private MobileNumberSegment matchMobileSegment(String number) {
        List<String> prefixes = new ArrayList<>();
        int maxLength = Math.min(7, number.length());
        for (int length = maxLength; length >= 3; length--) {
            prefixes.add(number.substring(0, length));
        }
        return mobileNumberSegmentMapper.selectList(new LambdaQueryWrapper<MobileNumberSegment>()
                .eq(MobileNumberSegment::getEnabled, true)
                .in(MobileNumberSegment::getSegmentPrefix, prefixes))
            .stream()
            .filter(segment -> number.startsWith(segment.getSegmentPrefix()))
            .max(Comparator.comparingInt(segment -> segment.getSegmentPrefix().length()))
            .orElse(null);
    }
}
