package org.dromara.license;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.license.LicenseResourceQuota.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class LicenseResourceQuotaTest {
    private final DeploymentIdentityRepository identities = mock(DeploymentIdentityRepository.class);
    private final LicenseFeatureGate gate = mock(LicenseFeatureGate.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LicenseResourceQuota quota = new LicenseResourceQuota(identities, gate, jdbc);

    @Test
    void checksEntireBatchAgainstRemainingExtensions() {
        valid(Map.of("extensions", 3));
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
        quota.lock();
        verify(identities).lock();
        assertDoesNotThrow(() -> quota.requireCapacity(Resource.EXTENSIONS, 1));
        assertThrows(ServiceException.class, () -> quota.requireCapacity(Resource.EXTENSIONS, 2));
    }

    @Test
    void zeroBlocksActivationEvenOnAnEmptyDeployment() {
        valid(Map.of("ai_robots", 0, "seats", 0));
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        assertThrows(ServiceException.class, () -> quota.requireCapacity(Resource.AI_ROBOTS, 1));
        assertThrows(ServiceException.class, () -> quota.requireCapacity(Resource.SEATS, 1));
    }

    @Test
    void validOlderLicensesWithoutALimitAndExplicitUnlimitedRemainUsable() {
        valid(Map.of("seats", -1));
        quota.requireCapacity(Resource.SEATS, 1);
        quota.requireCapacity(Resource.AI_ROBOTS, 1);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsNewCapacityWithoutValidAuthorization() {
        when(gate.readStatus()).thenReturn(status("EXPIRED", Map.of("seats", -1)));
        assertThrows(ServiceException.class, () -> quota.requireCapacity(Resource.SEATS, 1));
        verifyNoInteractions(jdbc);
    }

    private void valid(Map<String, Integer> limits) {
        when(gate.readStatus()).thenReturn(status("VALID", limits));
    }

    private LicenseManagementService.Status status(String state, Map<String, Integer> limits) {
        return new LicenseManagementService.Status(state, "deployment", null, true, null,
            null, null, null, Map.of(), limits, null);
    }
}
