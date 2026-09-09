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
    void rejectsRangesLongerThanThirtyOneDays() {
        ReportQuery query = new ReportQuery();
        query.setBeginDate("2026-08-01");
        query.setEndDate("2026-09-01");

        assertThrows(ServiceException.class,
            () -> ReportRangeResolver.resolve(query, LocalDate.of(2026, 9, 8)));
    }
}
