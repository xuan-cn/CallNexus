  package org.dromara.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.license.DeploymentIdentityRepository.PublicIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
@Tag("prod")
class LicenseRequestServiceTest {

    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DeploymentIdentityRepository repository = mock(DeploymentIdentityRepository.class);
    private final AtomicReference<PublicIdentity> persisted = new AtomicReference<>(new PublicIdentity(null, null, null));
    private LicenseRequestService service;

    @BeforeEach
    void setup() throws Exception {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        when(repository.lock()).thenAnswer(invocation -> persisted.get());
        doAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return null;
        }).when(repository).initialize(any());
        service = new LicenseRequestService(repository, new DeploymentIdentityStore(directory.toString()), mapper);
    }

    @Test
    void exportsVerifiableRequestWithoutPrivateKeyAndReusesIdentity() throws Exception {
        byte[] first = service.export();
        JsonNode envelope = mapper.readTree(first);
        String rawPayload = envelope.get("payload").asText();
        JsonNode payload = mapper.readTree(rawPayload);
        assertEquals(1, payload.get("version").asInt());
        assertEquals("CALLNEXUS", payload.get("product").asText());
        assertEquals(4, UUID.fromString(payload.get("deploymentId").asText()).version());

        byte[] publicKey = Base64.getDecoder().decode(payload.get("publicKey").asText());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey)),
            payload.get("publicKeyFingerprint").asText());
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey)));
        byte[] signature = Base64.getDecoder().decode(envelope.get("signature").asText());
        verifier.update(rawPayload.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(signature));
        verifier.update((rawPayload + " ").getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(signature));

        Path file = directory.resolve("deployment.identity");
        assertFalse(new String(first, StandardCharsets.UTF_8).contains(Files.readAllLines(file).get(2)));
        assertArrayEquals(first, service.export());
        verify(repository, times(1)).initialize(any());
    }

    @Test
    void recoversSameLocalIdentityAfterDatabaseWriteFailure() throws Exception {
        doThrow(new IllegalStateException("write failed")).doAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return null;
        }).when(repository).initialize(any());
        assertThrows(IllegalStateException.class, service::export);
        Path file = directory.resolve("deployment.identity");
        byte[] before = Files.readAllBytes(file);
        service.export();
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(Files.readAllLines(file).get(0), persisted.get().deploymentId());
    }

    @Test
    void refusesToReplaceAnIdentityWithMissingPrivateFile() throws Exception {
        service.export();
        Path file = directory.resolve("deployment.identity");
        Files.delete(file);
        assertThrows(ServiceException.class, service::export);
        assertFalse(Files.exists(file));
        verify(repository, times(1)).initialize(any());
    }

    @Test
    void rejectsMismatchedPublicIdentityWithoutOverwritingFile() throws Exception {
        service.export();
        Path file = directory.resolve("deployment.identity");
        List<String> before = Files.readAllLines(file);
        persisted.updateAndGet(identity -> new PublicIdentity(UUID.randomUUID().toString(), identity.publicKey(), identity.fingerprint()));
        assertThrows(ServiceException.class, service::export);
        assertEquals(before, Files.readAllLines(file));
        verify(repository, times(1)).initialize(any());
    }

    @Test
    void refusesToReplaceInvalidUncommittedIdentity() throws Exception {
        Path file = directory.resolve("deployment.identity");
        Files.writeString(file, "partial identity");
        assertThrows(ServiceException.class, service::export);
        assertEquals("partial identity", Files.readString(file));
        verify(repository, never()).initialize(any());
    }
}
