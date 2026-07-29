package org.dromara.customer.customer.service;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class CustomerPhoneNormalizer {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{5,20}$");

    public String normalize(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new ServiceException("客户电话号码不能为空");
        }
        String normalized = clean(phoneNumber);
        if (normalized.startsWith("00")) {
            normalized = "+" + normalized.substring(2);
        }
        if (!PHONE_PATTERN.matcher(normalized).matches()) {
            throw new ServiceException("客户电话号码格式无效，应为 5 至 20 位数字，可包含开头的加号");
        }
        return normalized;
    }

    public String clean(String phoneNumber) {
        return phoneNumber == null ? "" : phoneNumber.trim().replaceAll("[\\s\\-()（）]", "");
    }

    public boolean isValid(String phoneNumber) {
        String normalized = clean(phoneNumber);
        if (normalized.startsWith("00")) {
            normalized = "+" + normalized.substring(2);
        }
        return PHONE_PATTERN.matcher(normalized).matches();
    }
}
