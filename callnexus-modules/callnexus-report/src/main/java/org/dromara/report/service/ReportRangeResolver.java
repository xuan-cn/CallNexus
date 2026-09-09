package org.dromara.report.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.report.domain.request.ReportQuery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

final class ReportRangeResolver {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 31;

    private ReportRangeResolver() {
    }

    static ReportRange resolve(ReportQuery query, LocalDate today) {
        try {
            LocalDate end = query.getEndDate() == null || query.getEndDate().isBlank()
                ? today : LocalDate.parse(query.getEndDate());
            LocalDate begin = query.getBeginDate() == null || query.getBeginDate().isBlank()
                ? end.minusDays(DEFAULT_DAYS - 1L) : LocalDate.parse(query.getBeginDate());
            if (begin.isAfter(end)) throw new ServiceException("开始日期不能晚于结束日期");
            if (ChronoUnit.DAYS.between(begin, end) + 1 > MAX_DAYS) {
                throw new ServiceException("报表查询时间范围不能超过31天");
            }
            return new ReportRange(begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
        } catch (DateTimeParseException exception) {
            throw new ServiceException("日期格式必须为yyyy-MM-dd");
        }
    }

    static String granularity(ReportQuery query, ReportRange range) {
        if ("HOUR".equalsIgnoreCase(query.getGranularity())) return "HOUR";
        if ("DAY".equalsIgnoreCase(query.getGranularity())) return "DAY";
        return ChronoUnit.DAYS.between(range.startAt(), range.endAt()) == 1 ? "HOUR" : "DAY";
    }

    record ReportRange(LocalDateTime startAt, LocalDateTime endAt) {
    }
}
