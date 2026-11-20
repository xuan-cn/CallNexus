package org.dromara.license;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.license.DeploymentIdentityRepository.PublicIdentity;
import org.dromara.license.LicenseImportRepository.SavedLicense;
import org.dromara.license.LicenseVerifier.VerifiedLicense;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LicenseManagementService {

    private final DeploymentIdentityRepository identities;
    private final LicenseImportRepository imports;
    private final LicenseVerifier verifier;

    @Transactional(readOnly = true)
    public Status status() {
        PublicIdentity identity = identities.find();
        SavedLicense current = imports.latest();
        if (current == null) {
            return status(identity, null, null, "UNLICENSED", null);
        }
        try {
            return status(identity, verifier.verify(current.file(), identity), current.importedAt(), null, null);
        } catch (ServiceException e) {
            return status(identity, null, current.importedAt(), "INVALID", e.getMessage());
        }
    }

    @Transactional
    public Status importLicense(byte[] file, long operatorId) {
        // Serialize imports across instances and against the first identity export.
        PublicIdentity identity = identities.lock();
        if (!identity.initialized()) throw new ServiceException("请先导出授权申请，生成部署身份");
        VerifiedLicense incoming = verifier.verify(file, identity);
        Instant now = Instant.now();
        if (incoming.issuedAt().isAfter(now)) throw new ServiceException("授权尚未生效，请检查服务器时间");
        if (expired(incoming, now)) throw new ServiceException("授权已过期");

        SavedLicense current = imports.latest();
        if (current != null) {
            VerifiedLicense prior = verifier.verify(current.file(), identity);
            if (prior.signedPayload().equals(incoming.signedPayload())) {
                return status(identity, prior, current.importedAt(), null, null);
            }
            if (prior.licenseId().equals(incoming.licenseId()) || !incoming.issuedAt().isAfter(prior.issuedAt())) {
                throw new ServiceException("不能用旧授权或重复编号覆盖当前授权");
            }
        }
        imports.append(incoming.licenseId(), file, operatorId);
        return status(identity, incoming, imports.latest().importedAt(), null, null);
    }

    private Status status(PublicIdentity identity, VerifiedLicense license, Instant importedAt, String state, String message) {
        if (license == null) {
            return new Status(state, identity.deploymentId(), identity.fingerprint(), verifier.issuerConfigured(),
                null, null, null, importedAt, Map.of(), Map.of(), message);
        }
        Instant now = Instant.now();
        String validity = license.issuedAt().isAfter(now) ? "NOT_YET_VALID" : expired(license, now) ? "EXPIRED" : "VALID";
        return new Status(validity, identity.deploymentId(), identity.fingerprint(), verifier.issuerConfigured(),
            license.licenseId(), license.issuedAt(), license.expiresAt(), importedAt, license.features(), license.limits(), null);
    }

    private static boolean expired(VerifiedLicense license, Instant now) {
        return license.expiresAt() != null && !license.expiresAt().isAfter(now);
    }

    public record Status(String state, String deploymentId, String publicKeyFingerprint, boolean issuerConfigured,
                         String licenseId, Instant issuedAt, Instant expiresAt, Instant importedAt,
                         Map<String, Boolean> features, Map<String, Integer> limits, String message) { }
}
