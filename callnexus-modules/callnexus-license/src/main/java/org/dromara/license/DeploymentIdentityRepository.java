package org.dromara.license;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class DeploymentIdentityRepository {

    private final JdbcTemplate jdbc;

    PublicIdentity lock() {
        return read(true);
    }

    PublicIdentity find() {
        return read(false);
    }

    private PublicIdentity read(boolean lock) {
        return jdbc.queryForObject("""
            SELECT deployment_id, public_key, public_key_fingerprint
            FROM cc_license_deployment_identity WHERE singleton_id = 1
            """ + (lock ? "FOR UPDATE" : ""), (row, index) -> new PublicIdentity(row.getString(1), row.getString(2), row.getString(3)));
    }

    void initialize(PublicIdentity identity) {
        int changed = jdbc.update("""
            UPDATE cc_license_deployment_identity
            SET deployment_id = ?, public_key = ?, public_key_fingerprint = ?, created_at = CURRENT_TIMESTAMP
            WHERE singleton_id = 1 AND deployment_id IS NULL
            """, identity.deploymentId(), identity.publicKey(), identity.fingerprint());
        if (changed != 1) {
            throw new IllegalStateException("Deployment identity was already initialized");
        }
    }

    record PublicIdentity(String deploymentId, String publicKey, String fingerprint) {
        boolean initialized() {
            return deploymentId != null || publicKey != null || fingerprint != null;
        }
    }
}
