package org.dromara.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.license.DeploymentIdentityRepository.PublicIdentity;
import org.dromara.license.LicenseImportRepository.SavedLicense;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
@Tag("prod")
class LicenseManagementServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final KeyPair issuer;
    private final PublicIdentity identity = new PublicIdentity(UUID.randomUUID().toString(), "public", "a".repeat(64));
    private final DeploymentIdentityRepository identities = mock(DeploymentIdentityRepository.class);
    private final LicenseImportRepository imports = mock(LicenseImportRepository.class);
    private final AtomicReference<SavedLicense> current = new AtomicReference<>();
    private final LicenseManagementService service;

    LicenseManagementServiceTest() throws Exception {
        issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        service = new LicenseManagementService(identities, imports, new LicenseVerifier(base64(issuer.getPublic().getEncoded())));
        when(identities.lock()).thenReturn(identity);
        when(identities.find()).thenReturn(identity);
        when(imports.latest()).thenAnswer(invocation -> current.get());
        doAnswer(invocation -> {
            current.set(new SavedLicense(invocation.getArgument(1), Instant.now()));
            return null;
        }).when(imports).append(anyString(), any(byte[].class), anyLong());
    }

    @Test
    void importsAndRenewsWithoutAddingDuplicateHistory() throws Exception {
        assertEquals("UNLICENSED", service.status().state());
        Instant now = Instant.now();
        byte[] first = license(identity.deploymentId(), now.minusSeconds(60), now.plusSeconds(60));
        String firstId = service.importLicense(first, 1L).licenseId();
        assertEquals(firstId, service.importLicense(first, 1L).licenseId());
        byte[] renewal = license(identity.deploymentId(), now.minusSeconds(10), null);
        var status = service.importLicense(renewal, 1L);
        assertNotEquals(firstId, status.licenseId());
        assertEquals("VALID", service.status().state());
        assertNull(status.expiresAt());
        assertEquals(-1, status.limits().get("seats"));
        assertThrows(ServiceException.class, () -> service.importLicense(first, 1L));
        assertEquals(status.licenseId(), service.status().licenseId());
        verify(imports, times(2)).append(anyString(), any(byte[].class), eq(1L));
    }

    @Test
    void rejectsTamperedWrongDeploymentAndExpiredLicenseWithoutChangingActive() throws Exception {
        Instant now = Instant.now();
        byte[] active = license(identity.deploymentId(), now.minusSeconds(120), now.plusSeconds(300));
        service.importLicense(active, 1L);
        byte[] candidate = license(identity.deploymentId(), now.minusSeconds(30), now.plusSeconds(60));
        byte[] tampered = new String(candidate, StandardCharsets.UTF_8).replace("CALLNEXUS", "ALTERED").getBytes(StandardCharsets.UTF_8);
        byte[] other = license(UUID.randomUUID().toString(), now.minusSeconds(10), now.plusSeconds(60));
        byte[] expired = license(identity.deploymentId(), now.minusSeconds(60), now.minusSeconds(10));
        assertThrows(ServiceException.class, () -> service.importLicense(tampered, 1L));
        assertThrows(ServiceException.class, () -> service.importLicense(other, 1L));
        assertThrows(ServiceException.class, () -> service.importLicense(expired, 1L));
        assertArrayEquals(active, current.get().file());
        verify(imports, times(1)).append(anyString(), any(byte[].class), anyLong());
    }

    @Test
    void doesNotTrustKeyEmbeddedByUploaderAndDoesNotImportWithoutIdentity() throws Exception {
        Instant now = Instant.now();
        byte[] candidate = license(identity.deploymentId(), now.minusSeconds(5), null);
        var otherVerifier = new LicenseVerifier(base64(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded()));
        assertThrows(ServiceException.class, () -> otherVerifier.verify(candidate, identity));
        when(identities.lock()).thenReturn(new PublicIdentity(null, null, null));
        assertThrows(ServiceException.class, () -> service.importLicense(candidate, 1L));
        verify(imports, never()).append(anyString(), any(byte[].class), anyLong());
    }

    private byte[] license(String deploymentId, Instant issuedAt, Instant expiresAt) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("version", 1);
        fields.put("product", "CALLNEXUS");
        fields.put("licenseId", UUID.randomUUID().toString());
        fields.put("deploymentId", deploymentId);
        fields.put("publicKeyFingerprint", identity.fingerprint());
        fields.put("issuedAt", issuedAt.toString());
        fields.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        fields.put("features", Map.of("ai_voice", true));
        fields.put("limits", Map.of("seats", -1));
        String payload = JSON.writeValueAsString(fields);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(issuer.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return JSON.writeValueAsBytes(Map.of("payload", payload, "signature", base64(signer.sign()), "untrustedIssuer", base64(issuer.getPublic().getEncoded())));
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
