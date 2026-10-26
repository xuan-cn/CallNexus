package org.dromara.report.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.report.domain.request.ReportQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ReportRangeResolverTest {

    @Test
    void defaultsToSevenDaysAndDailyBuckets() {
        ReportQuery query = new ReportQuery();
        ReportRangeResolver.ReportRange range = ReportRangeResolver.resolve(query, LocalDate.of(2026, 9, 8));

        assertEquals("2026-09-02T00:00", range.startAt().toString());
        assertEquals("2026-09-09T00:00", range.endAt().toString());
        assertEquals("DAY", ReportRangeResolver.granularity(query, range));
    }

    @Test
    void usesHourlyBucketsForOneDay() {
        ReportQuery query = new ReportQuery();
        query.setBeginDate("2026-09-08");
        query.setEndDate("2026-09-08");

        ReportRangeResolver.ReportRange range = ReportRangeResolver.resolve(query, LocalDate.of(2026, 9, 8));

        assertEquals("HOUR", ReportRangeResolver.granularity(query, range));
    }

    @Test
    void preservesSecondPrecisionAndIncludesSelectedEndSecond() {
        ReportQuery query = new ReportQuery();
        query.setBeginDate("2026-09-08 10:15:20");
        query.setEndDate("2026-09-08 11:25:30");

        ReportRangeResolver.ReportRange range = ReportRangeResolver.resolve(query, LocalDate.of(2026, 9, 8));

        assertEquals("2026-09-08T10:15:20", range.startAt().toString());
        assertEquals("2026-09-08T11:25:31", range.endAt().toString());
    }

    @Test
    void rejectsReversedSecondPrecisionRange() {
        ReportQuery query = new ReportQuery();
        query.setBeginDate("2026-09-08 11:25:31");
        query.setEndDate("2026-09-08 11:25:30");

        assertThrows(ServiceException.class,
            () -> ReportRangeResolver.resolve(query, LocalDate.of(2026, 9, 8)));
    }

    @Test
    void rejectsRangesLongerThanThirtyOneDays() {
        ReportQuery query = new ReportQuery();
        query.setBeginDate("2026-08-01");
        query.setEndDate("2026-09-01");

        assertThrows(ServiceException.class,
            () -> ReportRangeResolver.resolve(query, LocalDate.of(2026, 9, 8)));
    }

    @Test
    void allowsDailyBucketsForUpToOneYear() {
        ReportQuery query = new ReportQuery();
        query.setGranularity("DAY");
        query.setBeginDate("2026-01-01");
        query.setEndDate("2026-12-31");

        ReportRangeResolver.ReportRange range = ReportRangeResolver.resolve(query, LocalDate.of(2026, 12, 31));

        assertEquals("DAY", ReportRangeResolver.granularity(query, range));
    }

    @Test
    void allowsMonthlyBucketsForUpToFiveYears() {
        ReportQuery query = new ReportQuery();
        query.setGranularity("MONTH");
        query.setBeginDate("2022-01-01");
        query.setEndDate("2026-12-31");

        ReportRangeResolver.ReportRange range = ReportRangeResolver.resolve(query, LocalDate.of(2026, 12, 31));

        assertEquals("MONTH", ReportRangeResolver.granularity(query, range));
    }

    @Test
    void keepsHourlyBucketsLimitedToThirtyOneDays() {
        ReportQuery query = new ReportQuery();
        query.setGranularity("HOUR");
        query.setBeginDate("2026-08-01");
        query.setEndDate("2026-09-01");

        assertThrows(ServiceException.class,
            () -> ReportRangeResolver.resolve(query, LocalDate.of(2026, 9, 8)));
    }
}
