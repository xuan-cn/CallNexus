package org.dromara.call.service.impl;

import org.dromara.call.domain.TelephonyEvent;
import org.dromara.common.core.utils.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Resolves one canonical occurrence time for every persistence projection of a telephony event.
 */
final class TelephonyEventTimeResolver {

    private static final DateTimeFormatter FS_LOCAL_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TelephonyEventTimeResolver() {
    }

    static LocalDateTime resolve(TelephonyEvent event) {
        String timestamp = event.headers().get("Event-Date-Timestamp");
        if (StringUtils.isNotBlank(timestamp) && timestamp.matches("^\\d+$")) {
            try {
                long micros = Long.parseLong(timestamp);
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(micros / 1000), ZoneId.systemDefault());
            } catch (NumberFormatException ignored) {
                // Fall through to Event-Date-Local / now.
            }
        }
        String local = event.headers().get("Event-Date-Local");
        if (StringUtils.isNotBlank(local)) {
            try {
                return LocalDateTime.parse(local, FS_LOCAL_TIME_FORMATTER);
            } catch (DateTimeParseException ignored) {
                // Fall through to now.
            }
        }
        return LocalDateTime.now();
    }
}
