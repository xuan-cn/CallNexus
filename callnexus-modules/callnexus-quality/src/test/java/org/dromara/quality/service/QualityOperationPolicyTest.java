package org.dromara.quality.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityOperationPolicyTest {
    @Test
    void resolvesPreviousCompleteDayAndWeek() {
        LocalDate today = LocalDate.of(2026, 9, 18);
        QualityOperationPolicy.PeriodRange daily = QualityOperationPolicy.previousPeriod("DAILY", today);
        QualityOperationPolicy.PeriodRange weekly = QualityOperationPolicy.previousPeriod("WEEKLY", today);

        assertEquals(LocalDate.of(2026, 9, 17), daily.start());
        assertEquals(LocalDate.of(2026, 9, 17), daily.end());
        assertEquals(LocalDate.of(2026, 9, 7), weekly.start());
        assertEquals(LocalDate.of(2026, 9, 13), weekly.end());
    }

    @Test
    void evaluatesThresholdOperatorsAtBoundary() {
        BigDecimal actual = new BigDecimal("80.00");
        BigDecimal threshold = new BigDecimal("80");
        assertFalse(QualityOperationPolicy.breached(actual, "LT", threshold));
        assertTrue(QualityOperationPolicy.breached(actual, "LTE", threshold));
        assertFalse(QualityOperationPolicy.breached(actual, "GT", threshold));
        assertTrue(QualityOperationPolicy.breached(actual, "GTE", threshold));
    }
}
