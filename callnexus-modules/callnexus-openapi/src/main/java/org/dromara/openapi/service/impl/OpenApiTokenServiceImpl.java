package org.dromara.openapi.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.GlobalConstants;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.openapi.domain.OpenApiApplication;
import org.dromara.openapi.domain.OpenApiApplicationScope;
import org.dromara.openapi.domain.OpenApiCredential;
import org.dromara.openapi.domain.OpenApiIpRule;
import org.dromara.openapi.domain.response.OpenApiTokenResponse;
import org.dromara.openapi.mapper.OpenApiApplicationMapper;
import org.dromara.openapi.mapper.OpenApiApplicationScopeMapper;
import org.dromara.openapi.mapper.OpenApiCredentialMapper;
import org.dromara.openapi.mapper.OpenApiIpRuleMapper;
import org.dromara.openapi.security.IpCidrMatcher;
import org.dromara.openapi.security.OpenApiAuthenticationException;
import org.dromara.openapi.security.OpenApiPrincipal;
import org.dromara.openapi.security.OpenApiSecretGenerator;
import org.dromara.openapi.service.OpenApiTokenService;
import org.redisson.api.RateType;
import org.redisson.client.RedisException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OpenApiTokenServiceImpl implements OpenApiTokenService {
    private static final String TOKEN_KEY = GlobalConstants.GLOBAL_REDIS_KEY + "openapi:access:";
    private static final String RATE_KEY = GlobalConstants.GLOBAL_REDIS_KEY + "openapi:rate:";
    private static final String AUTH_RATE_KEY = GlobalConstants.GLOBAL_REDIS_KEY + "openapi:auth-rate:";

    private final OpenApiApplicationMapper applicationMapper;
    private final OpenApiCredentialMapper credentialMapper;
    private final OpenApiApplicationScopeMapper scopeMapper;
    private final OpenApiIpRuleMapper ipRuleMapper;

    @Override
    public OpenApiTokenResponse issue(String grantType, String clientId, String clientSecret,
                                      String requestedScope, String remoteAddress) {
        if (!"client_credentials".equals(grantType)) {
            throw auth(400, "unsupported_grant_type", "Only client_credentials is supported.");
        }
        if (clientId == null || clientSecret == null) {
            throw auth(401, "invalid_client", "Client credentials are required.");
        }
        String attemptKey = AUTH_RATE_KEY + OpenApiSecretGenerator.digest(clientId + ":" + remoteAddress);
        if (RedisUtils.rateLimiter(attemptKey, RateType.OVERALL, 20, 60) < 0) {
            throw auth(429, "rate_limit_exceeded", "Too many token requests.");
        }
        OpenApiCredential credential = TenantHelper.ignore(() -> credentialMapper.selectOne(
            new LambdaQueryWrapper<OpenApiCredential>().eq(OpenApiCredential::getClientId, clientId)));
        if (credential == null || !"ACTIVE".equals(credential.getStatus())
            || !BCrypt.checkpw(clientSecret, credential.getSecretHash())
            || expired(credential.getExpiresAt())) {
            throw auth(401, "invalid_client", "Client credentials are invalid or expired.");
        }
        OpenApiApplication application = TenantHelper.ignore(() -> applicationMapper.selectById(credential.getApplicationId()));
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) {
            throw auth(401, "invalid_client", "OpenAPI application is disabled.");
        }
        verifySourceAddress(application.getId(), remoteAddress);
        Set<String> configuredScopes = configuredScopes(application.getId());
        Set<String> issuedScopes = requestedScopes(requestedScope, configuredScopes);
        int expiresIn = application.getTokenTtlSeconds() == null ? 3600 : application.getTokenTtlSeconds();
        String token = OpenApiSecretGenerator.accessToken();
        OpenApiPrincipal principal = new OpenApiPrincipal(application.getId(), credential.getId(),
            credential.getVersion(), application.getTenantId(), application.getAppCode(), issuedScopes);
        RedisUtils.setCacheObject(TOKEN_KEY + OpenApiSecretGenerator.digest(token),
            JsonUtils.toJsonString(principal), Duration.ofSeconds(expiresIn));
        TenantHelper.dynamic(application.getTenantId(), () -> credentialMapper.update(null,
            new LambdaUpdateWrapper<OpenApiCredential>().eq(OpenApiCredential::getId, credential.getId())
                .set(OpenApiCredential::getLastUsedAt, new Date())));
        return new OpenApiTokenResponse(token, "Bearer", expiresIn, String.join(" ", issuedScopes));
    }

    @Override
    public OpenApiPrincipal authenticate(String accessToken, String remoteAddress) {
        if (accessToken == null || accessToken.isBlank()) {
            throw auth(401, "invalid_token", "Bearer token is required.");
        }
        String tokenKey = TOKEN_KEY + OpenApiSecretGenerator.digest(accessToken);
        OpenApiPrincipal issued = readPrincipal(tokenKey);
        if (issued == null) {
            throw auth(401, "invalid_token", "Bearer token is invalid or expired.");
        }
        OpenApiCredential credential = TenantHelper.ignore(() -> credentialMapper.selectById(issued.credentialId()));
        if (credential == null || !"ACTIVE".equals(credential.getStatus()) || expired(credential.getExpiresAt())
            || !issued.credentialVersion().equals(credential.getVersion())) {
            RedisUtils.deleteObject(tokenKey);
            throw auth(401, "invalid_token", "Credential was revoked, rotated or expired.");
        }
        OpenApiApplication application = TenantHelper.ignore(() -> applicationMapper.selectById(issued.applicationId()));
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) {
            RedisUtils.deleteObject(tokenKey);
            throw auth(401, "invalid_token", "OpenAPI application is disabled.");
        }
        verifySourceAddress(application.getId(), remoteAddress);
        Set<String> currentScopes = configuredScopes(application.getId());
        Set<String> effectiveScopes = issued.scopes().stream()
            .filter(currentScopes::contains).collect(Collectors.toCollection(LinkedHashSet::new));
        int requestsPerMinute = application.getRequestsPerMinute() == null ? 120 : application.getRequestsPerMinute();
        if (RedisUtils.rateLimiter(RATE_KEY + application.getId(), RateType.OVERALL, requestsPerMinute, 60) < 0) {
            throw auth(429, "rate_limit_exceeded", "OpenAPI request rate limit exceeded.");
        }
        return new OpenApiPrincipal(application.getId(), credential.getId(), credential.getVersion(),
            application.getTenantId(), application.getAppCode(), effectiveScopes);
    }

    private Set<String> requestedScopes(String requestedScope, Set<String> configuredScopes) {
        if (requestedScope == null || requestedScope.isBlank()) {
            return new LinkedHashSet<>(configuredScopes);
        }
        Set<String> requested = Arrays.stream(requestedScope.trim().split("\\s+"))
            .filter(value -> !value.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!configuredScopes.containsAll(requested)) {
            throw auth(400, "invalid_scope", "Requested scope exceeds the application authorization.");
        }
        return requested;
    }

    private Set<String> configuredScopes(Long applicationId) {
        List<OpenApiApplicationScope> values = TenantHelper.ignore(() -> scopeMapper.selectList(
            new LambdaQueryWrapper<OpenApiApplicationScope>()
                .eq(OpenApiApplicationScope::getApplicationId, applicationId)
                .orderByAsc(OpenApiApplicationScope::getScopeCode)));
        return values.stream().map(OpenApiApplicationScope::getScopeCode)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void verifySourceAddress(Long applicationId, String remoteAddress) {
        List<OpenApiIpRule> rules = TenantHelper.ignore(() -> ipRuleMapper.selectList(
            new LambdaQueryWrapper<OpenApiIpRule>()
                .eq(OpenApiIpRule::getApplicationId, applicationId)
                .eq(OpenApiIpRule::getEnabled, true)));
        if (rules.isEmpty() || rules.stream().noneMatch(rule -> IpCidrMatcher.matches(remoteAddress, rule.getCidr()))) {
            throw auth(403, "ip_not_allowed", "Source IP is not allowed for this application.");
        }
    }

    private boolean expired(Date expiresAt) {
        return expiresAt != null && !expiresAt.after(new Date());
    }

    private OpenApiPrincipal readPrincipal(String tokenKey) {
        try {
            String value = RedisUtils.getCacheObject(tokenKey);
            return JsonUtils.parseObject(value, OpenApiPrincipal.class);
        } catch (RedisException | IllegalArgumentException exception) {
            RedisUtils.deleteObject(tokenKey);
            throw auth(401, "invalid_token", "Bearer token format is obsolete. Request a new token.");
        }
    }

    private OpenApiAuthenticationException auth(int status, String error, String message) {
        return new OpenApiAuthenticationException(status, error, message);
    }
}
