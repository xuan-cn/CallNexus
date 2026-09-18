package org.dromara.quality.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.quality.domain.request.QualityReportQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QualityReportRangeResolverTest {
    @Test
    void defaultsToRecentSevenDays() {
        QualityReportRangeResolver.Range range = QualityReportRangeResolver.resolve(new QualityReportQuery(),
            LocalDate.of(2026, 9, 17));
        assertEquals("2026-09-11T00:00", range.startAt().toString());
        assertEquals("2026-09-18T00:00", range.endAt().toString());
    }

    @Test
    void rejectsRangesLongerThanThirtyOneDays() {
        QualityReportQuery query = new QualityReportQuery();
        query.setBeginDate("2026-08-01");
        query.setEndDate("2026-09-17");
        assertThrows(ServiceException.class,
            () -> QualityReportRangeResolver.resolve(query, LocalDate.of(2026, 9, 17)));
    }
}
