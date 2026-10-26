package org.dromara.report.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.report.domain.request.ReportQuery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

final class ReportRangeResolver {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_HOUR_DAYS = 31;
    private static final int MAX_DAY_DAYS = 366;
    private static final int MAX_MONTH_DAYS = 1_827;

    private ReportRangeResolver() {
    }

    static ReportRange resolve(ReportQuery query, LocalDate today) {
        try {
            LocalDate endDate;
            LocalDateTime endAt;
            if (query.getEndDate() == null || query.getEndDate().isBlank()) {
                endDate = today;
                endAt = today.plusDays(1).atStartOfDay();
            } else if (isDateOnly(query.getEndDate())) {
                endDate = LocalDate.parse(query.getEndDate());
                endAt = endDate.plusDays(1).atStartOfDay();
            } else {
                LocalDateTime selectedEnd = parseDateTime(query.getEndDate());
                endDate = selectedEnd.toLocalDate();
                endAt = selectedEnd.plusSeconds(1);
            }

            LocalDateTime startAt = query.getBeginDate() == null || query.getBeginDate().isBlank()
                ? endDate.minusDays(DEFAULT_DAYS - 1L).atStartOfDay()
                : parseStart(query.getBeginDate());
            if (!startAt.isBefore(endAt)) throw new ServiceException("开始时间不能晚于结束时间");
            long days = ChronoUnit.DAYS.between(startAt.toLocalDate(), endDate) + 1;
            int maximumDays = maximumDays(query.getGranularity());
            if (days > maximumDays) {
                throw new ServiceException("当前时间粒度查询范围不能超过" + maximumDays + "天");
            }
            return new ReportRange(startAt, endAt);
        } catch (DateTimeParseException exception) {
            throw new ServiceException("时间格式必须为yyyy-MM-dd或yyyy-MM-dd HH:mm:ss");
        }
    }

    private static LocalDateTime parseStart(String value) {
        return isDateOnly(value) ? LocalDate.parse(value).atStartOfDay() : parseDateTime(value);
    }

    private static LocalDateTime parseDateTime(String value) {
        return LocalDateTime.parse(value.replace(' ', 'T'));
    }

    private static boolean isDateOnly(String value) {
        return value.length() == 10;
    }

    static String granularity(ReportQuery query, ReportRange range) {
        if ("HOUR".equalsIgnoreCase(query.getGranularity())) return "HOUR";
        if ("DAY".equalsIgnoreCase(query.getGranularity())) return "DAY";
        if ("MONTH".equalsIgnoreCase(query.getGranularity())) return "MONTH";
        return ChronoUnit.DAYS.between(range.startAt(), range.endAt()) == 1 ? "HOUR" : "DAY";
    }

    private static int maximumDays(String granularity) {
        if ("MONTH".equalsIgnoreCase(granularity)) return MAX_MONTH_DAYS;
        if ("DAY".equalsIgnoreCase(granularity)) return MAX_DAY_DAYS;
        return MAX_HOUR_DAYS;
    }

    record ReportRange(LocalDateTime startAt, LocalDateTime endAt) {
    }
}
