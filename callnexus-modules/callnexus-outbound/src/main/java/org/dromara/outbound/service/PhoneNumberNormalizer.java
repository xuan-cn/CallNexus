package org.dromara.outbound.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class PhoneNumberNormalizer {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{5,20}$");

    public String normalize(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            return null;
        }
        String value = phone.trim().replaceAll("[\\s\\-()（）]", "");
        return value.startsWith("00") ? "+" + value.substring(2) : value;
    }

    public boolean isValid(String normalizedPhone) {
        return normalizedPhone != null && PHONE_PATTERN.matcher(normalizedPhone).matches();
    }
}
