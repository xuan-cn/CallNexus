package org.dromara.license;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LicenseResourceQuota {
    private final DeploymentIdentityRepository identities;
    private final LicenseFeatureGate gate;
    private final JdbcTemplate jdbc;

    // The caller must take this lock before its first read, and hold it through the write.
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        identities.lock();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCapacity(Resource resource, int additional) {
        var status = gate.readStatus();
        String denial = LicenseFeatureGate.denial(status);
        if (denial != null) throw new ServiceException(denial);
        int limit = status.limits().getOrDefault(resource.code, -1);
        if (limit == -1) return;
        Long count = jdbc.queryForObject(resource.countSql, Long.class);
        if (count == null || count + additional > limit) {
            throw new ServiceException(resource.label + "授权额度不足（已用 " + count
                + "，本次需要 " + additional + "，上限 " + limit + "）");
        }
    }

    public enum Resource {
        SEATS("seats", "坐席", "SELECT COUNT(*) FROM cc_agent WHERE deleted = 0 AND enabled = 1"),
        AI_ROBOTS("ai_robots", "AI 流程机器人", "SELECT COUNT(*) FROM cc_ai_workflow WHERE deleted = 0 AND enabled = 1"),
        EXTENSIONS("extensions", "分机", "SELECT COUNT(*) FROM cc_sip_account WHERE deleted = 0");

        private final String code;
        private final String label;
        private final String countSql;

        Resource(String code, String label, String countSql) {
            this.code = code;
            this.label = label;
            this.countSql = countSql;
        }
    }
}
