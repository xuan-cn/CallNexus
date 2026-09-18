package org.dromara.quality.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityDataScopeServiceTest {

    @Test
    void unrestrictedScopeAllowsAllQualityData() {
        QualityDataScopeService.Scope scope = new QualityDataScopeService.Scope(false, Set.of(), Set.of());

        assertTrue(scope.allows(1001L, 2001L));
        assertTrue(scope.allows(null, null));
    }

    @Test
    void taskWithAgentMustMatchAgentScopeEvenWhenQueueIsAllowed() {
        QualityDataScopeService.Scope scope = new QualityDataScopeService.Scope(true, Set.of(1001L), Set.of(2001L));

        assertTrue(scope.allows(1001L, 9999L));
        assertFalse(scope.allows(1002L, 2001L));
    }

    @Test
    void taskWithoutAgentUsesQueueScope() {
        QualityDataScopeService.Scope scope = new QualityDataScopeService.Scope(true, Set.of(1001L), Set.of(2001L));

        assertTrue(scope.allows(null, 2001L));
        assertFalse(scope.allows(null, 2002L));
        assertFalse(scope.allows(null, null));
    }
}
