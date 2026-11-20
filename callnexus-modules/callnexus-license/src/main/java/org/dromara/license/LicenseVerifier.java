package org.dromara.license;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.license.DeploymentIdentityRepository.PublicIdentity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
class LicenseVerifier {

    static final int MAX_BYTES = 32 * 1024;
    private static final String ALGORITHM = "Ed25519";
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final String issuerKey;

    LicenseVerifier() {
        this(packagedIssuerKey());
    }

    LicenseVerifier(String issuerKey) {
        this.issuerKey = issuerKey;
    }

    private static String packagedIssuerKey() {
        try (InputStream file = LicenseVerifier.class.getResourceAsStream("issuer-public-key.txt")) {
            return file == null ? "" : new String(file.readAllBytes(), StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the packaged issuer key", e);
        }
    }

    boolean issuerConfigured() {
        return !issuerKey.isBlank();
    }

    VerifiedLicense verify(byte[] file, PublicIdentity identity) {
        PublicKey issuer = trustedKey();
        if (file.length == 0 || file.length > MAX_BYTES) {
            throw new ServiceException("授权文件必须为 1 至 32768 字节");
        }
        try {
            JsonNode envelope = JSON.readTree(file);
            String payload = requiredText(envelope, "payload");
            byte[] signature = Base64.getDecoder().decode(requiredText(envelope, "signature"));
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(issuer);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            if (signature.length != 64 || !verifier.verify(signature)) {
                throw new ServiceException("授权签名无效");
            }

            JsonNode data = JSON.readTree(payload);
            if (!data.path("version").isIntegralNumber() || !data.path("version").canConvertToInt()
                || data.path("version").intValue() != 1 || !"CALLNEXUS".equals(requiredText(data, "product"))) {
                throw invalidFile();
            }
            if (!identity.initialized() || !requiredText(data, "deploymentId").equals(identity.deploymentId())
                || !requiredText(data, "publicKeyFingerprint").equals(identity.fingerprint())) {
                throw new ServiceException("授权不属于当前部署");
            }
            String id = requiredText(data, "licenseId");
            if (!UUID.fromString(id).toString().equals(id)) {
                throw invalidFile();
            }
            Instant issued = Instant.parse(requiredText(data, "issuedAt"));
            JsonNode expiresNode = data.path("expiresAt");
            Instant expires = expiresNode.isNull() ? null : Instant.parse(requiredText(data, "expiresAt"));
            if (expires != null && !expires.isAfter(issued)) {
                throw invalidFile();
            }
            Map<String, Boolean> features = new LinkedHashMap<>();
            entries(data, "features").fields().forEachRemaining(field -> {
                if (!validCode(field.getKey()) || !field.getValue().isBoolean()) throw invalidFile();
                features.put(field.getKey(), field.getValue().booleanValue());
            });
            Map<String, Integer> limits = new LinkedHashMap<>();
            entries(data, "limits").fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (!validCode(field.getKey()) || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < -1) {
                    throw invalidFile();
                }
                limits.put(field.getKey(), value.intValue());
            });
            return new VerifiedLicense(id, issued, expires, features, limits, payload);
        } catch (IOException | IllegalArgumentException | DateTimeParseException e) {
            throw invalidFile();
        } catch (GeneralSecurityException e) {
            throw new ServiceException("授权签名无效");
        }
    }

    private PublicKey trustedKey() {
        if (!issuerConfigured()) throw new ServiceException("未配置发行方授权公钥，请联系部署管理员");
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(issuerKey.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ServiceException("发行方授权公钥格式无效");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalidFile();
        return value.textValue();
    }

    private static JsonNode entries(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isObject() || value.size() > 64) throw invalidFile();
        return value;
    }

    private static boolean validCode(String key) {
        return key.matches("[a-z][a-z0-9_]{0,63}");
    }

    private static ServiceException invalidFile() {
        return new ServiceException("授权文件格式无效");
    }

    record VerifiedLicense(String licenseId, Instant issuedAt, Instant expiresAt, Map<String, Boolean> features,
                           Map<String, Integer> limits, String signedPayload) { }
}
