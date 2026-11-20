package org.dromara.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.license.DeploymentIdentityRepository.PublicIdentity;
import org.dromara.license.DeploymentIdentityStore.LocalIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LicenseRequestService {

    private static final String ALGORITHM = "Ed25519";

    private final DeploymentIdentityRepository repository;
    private final DeploymentIdentityStore store;
    private final ObjectMapper mapper;

    @Transactional
    public byte[] export() {
        store.requireConfigured();
        PublicIdentity existing = repository.lock();
        try {
            LocalIdentity local = store.read();
            if (local == null && existing.initialized()) {
                throw new ServiceException("部署身份已存在，但当前实例没有私钥文件，请在管理实例操作或恢复原身份备份");
            }
            boolean needsWrite = local == null;
            if (needsWrite) {
                KeyPair keys = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
                local = new LocalIdentity(UUID.randomUUID().toString(), encode(keys.getPublic().getEncoded()),
                    encode(keys.getPrivate().getEncoded()));
            }

            if (!UUID.fromString(local.deploymentId()).toString().equals(local.deploymentId())) {
                throw new IllegalArgumentException("Invalid deployment ID");
            }
            byte[] publicKey = Base64.getDecoder().decode(local.publicKey());
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey));
            PublicIdentity identity = new PublicIdentity(local.deploymentId(), local.publicKey(), fingerprint);
            if (existing.initialized() && !existing.equals(identity)) {
                throw new ServiceException("部署身份文件与数据库不匹配，拒绝导出，请恢复对应备份");
            }

            byte[] payload = mapper.writeValueAsBytes(new RequestPayload(1, "CALLNEXUS", ALGORITHM,
                identity.deploymentId(), identity.publicKey(), identity.fingerprint()));
            byte[] signature = signAndVerify(local, publicKey, payload);

            // If the database commit fails after the atomic write, retry the same file under the row lock.
            if (needsWrite) {
                store.write(local);
            }
            if (!existing.initialized()) {
                repository.initialize(identity);
            }
            return mapper.writeValueAsBytes(new RequestEnvelope(new String(payload, StandardCharsets.UTF_8), encode(signature)));
        } catch (IOException e) {
            throw new ServiceException("无法读写部署身份，请检查持久化目录或恢复备份");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ServiceException("部署身份密钥不可用，请确认 Java 支持 Ed25519 并检查身份备份");
        }
    }

    private static byte[] signAndVerify(LocalIdentity local, byte[] publicKey, byte[] payload) throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
        Signature signature = Signature.getInstance(ALGORITHM);
        signature.initSign(factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(local.privateKey()))));
        signature.update(payload);
        byte[] signed = signature.sign();
        signature.initVerify(factory.generatePublic(new X509EncodedKeySpec(publicKey)));
        signature.update(payload);
        if (!signature.verify(signed)) {
            throw new GeneralSecurityException("Deployment key pair does not match");
        }
        return signed;
    }

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private record RequestPayload(int version, String product, String algorithm, String deploymentId,
                                  String publicKey, String publicKeyFingerprint) { }

    private record RequestEnvelope(String payload, String signature) { }
}
