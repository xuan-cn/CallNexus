package org.dromara.license;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
@RequiredArgsConstructor
class LicenseImportRepository {

    private final JdbcTemplate jdbc;

    SavedLicense latest() {
        return jdbc.query("""
            SELECT license_file, imported_at FROM cc_license_import ORDER BY id DESC LIMIT 1
            """, rows -> rows.next() ? new SavedLicense(rows.getBytes(1), rows.getTimestamp(2).toInstant()) : null);
    }

    void append(String licenseId, byte[] file, long operatorId) {
        jdbc.update("INSERT INTO cc_license_import (license_id, license_file, imported_by) VALUES (?, ?, ?)",
            licenseId, file, operatorId);
    }

    record SavedLicense(byte[] file, Instant importedAt) { }
}
