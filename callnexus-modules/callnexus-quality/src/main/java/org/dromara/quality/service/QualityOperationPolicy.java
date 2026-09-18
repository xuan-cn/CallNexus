package org.dromara.quality.service;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

final class QualityOperationPolicy {
    private QualityOperationPolicy() {
    }

    static PeriodRange previousPeriod(String periodType, LocalDate today) {
        return switch (periodType) {
            case "DAILY" -> new PeriodRange(today.minusDays(1), today.minusDays(1));
            case "WEEKLY" -> {
                LocalDate end = today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY)).minusDays(1);
                yield new PeriodRange(end.minusDays(6), end);
            }
            default -> throw new ServiceException("不支持的周期类型");
        };
    }

    static PeriodRange endingAt(String periodType, LocalDate periodEnd) {
        if (periodEnd == null) return previousPeriod(periodType, LocalDate.now());
        return switch (periodType) {
            case "DAILY" -> new PeriodRange(periodEnd, periodEnd);
            case "WEEKLY" -> new PeriodRange(periodEnd.minusDays(6), periodEnd);
            default -> throw new ServiceException("不支持的周期类型");
        };
    }

    static boolean breached(BigDecimal actual, String operator, BigDecimal threshold) {
        int compared = actual.compareTo(threshold);
        return switch (operator) {
            case "LT" -> compared < 0;
            case "LTE" -> compared <= 0;
            case "GT" -> compared > 0;
            case "GTE" -> compared >= 0;
            default -> throw new ServiceException("不支持的比较方式");
        };
    }

    record PeriodRange(LocalDate start, LocalDate end) {
    }
}
