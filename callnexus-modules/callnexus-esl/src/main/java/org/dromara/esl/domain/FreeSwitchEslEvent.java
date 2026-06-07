package org.dromara.esl.domain;

import java.util.Map;

public record FreeSwitchEslEvent(Long nodeId, String eventName, Map<String, String> headers) {
    public String header(String name) {
        return headers.get(name);
    }

    public String firstHeader(String... names) {
        for (String name : names) {
            String value = header(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
