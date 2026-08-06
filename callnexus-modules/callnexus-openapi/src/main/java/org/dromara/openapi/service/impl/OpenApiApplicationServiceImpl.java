package org.dromara.openapi.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.openapi.domain.OpenApiApplication;
import org.dromara.openapi.domain.OpenApiApplicationScope;
import org.dromara.openapi.domain.OpenApiCredential;
import org.dromara.openapi.domain.OpenApiIpRule;
import org.dromara.openapi.domain.OpenApiRouteGrant;
import org.dromara.openapi.domain.request.OpenApiApplicationRequest;
import org.dromara.openapi.domain.request.OpenApiCredentialRequest;
import org.dromara.openapi.domain.request.OpenApiIpRuleRequest;
import org.dromara.openapi.domain.response.OpenApiApplicationResponse;
import org.dromara.openapi.domain.response.OpenApiCredentialResponse;
import org.dromara.openapi.domain.response.OpenApiCredentialSecretResponse;
import org.dromara.openapi.domain.response.OpenApiIpRuleResponse;
import org.dromara.openapi.mapper.OpenApiApplicationMapper;
import org.dromara.openapi.mapper.OpenApiApplicationScopeMapper;
import org.dromara.openapi.mapper.OpenApiCredentialMapper;
import org.dromara.openapi.mapper.OpenApiIpRuleMapper;
import org.dromara.openapi.mapper.OpenApiRouteGrantMapper;
import org.dromara.openapi.security.IpCidrMatcher;
import org.dromara.openapi.security.OpenApiScopeCatalog;
import org.dromara.openapi.security.OpenApiSecretGenerator;
import org.dromara.openapi.event.OpenApiEventTypeCatalog;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.openapi.service.OpenApiApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.net.URI;

@Service
@RequiredArgsConstructor
public class OpenApiApplicationServiceImpl implements OpenApiApplicationService {
    private static final String ACTIVE = "ACTIVE";
    private static final String REVOKED = "REVOKED";
    private static final String SECRET_WARNING = "Client secret is shown only once. Store it securely.";

    private final OpenApiApplicationMapper applicationMapper;
    private final OpenApiCredentialMapper credentialMapper;
    private final OpenApiApplicationScopeMapper scopeMapper;
    private final OpenApiIpRuleMapper ipRuleMapper;
    private final OpenApiRouteGrantMapper routeGrantMapper;

    @Override
    public List<OpenApiApplicationResponse> list() {
        return applicationMapper.selectList(new LambdaQueryWrapper<OpenApiApplication>()
                .orderByAsc(OpenApiApplication::getAppCode))
            .stream().map(this::response).toList();
    }

    @Override
    public OpenApiApplicationResponse get(Long id) {
        return response(requireApplication(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(OpenApiApplicationRequest request) {
        validateRequest(request, null);
        OpenApiApplication application = new OpenApiApplication();
        fill(application, request);
        applicationMapper.insert(application);
        replacePolicies(application.getId(), request);
        return application.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, OpenApiApplicationRequest request) {
        if (request.getVersion() == null) {
            throw new ServiceException("Missing application version. Refresh and retry.");
        }
        validateRequest(request, id);
        OpenApiApplication application = requireApplication(id);
        fill(application, request);
        application.setVersion(request.getVersion());
        if (applicationMapper.updateById(application) != 1) {
            throw new ServiceException("The application was changed by another user. Refresh and retry.");
        }
        replacePolicies(id, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireApplication(id);
        credentialMapper.update(null, new LambdaUpdateWrapper<OpenApiCredential>()
            .eq(OpenApiCredential::getApplicationId, id).set(OpenApiCredential::getStatus, REVOKED));
        deletePolicies(id);
        if (applicationMapper.deleteById(id) != 1) {
            throw new ServiceException("OpenAPI application does not exist.");
        }
    }

    @Override
    public Set<String> availableScopes() {
        return OpenApiScopeCatalog.ordered();
    }

    @Override
    public List<OpenApiCredentialResponse> credentials(Long applicationId) {
        requireApplication(applicationId);
        return credentialMapper.selectList(new LambdaQueryWrapper<OpenApiCredential>()
                .eq(OpenApiCredential::getApplicationId, applicationId)
                .orderByDesc(OpenApiCredential::getCreateTime))
            .stream().map(this::credentialResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OpenApiCredentialSecretResponse createCredential(Long applicationId, OpenApiCredentialRequest request) {
        requireApplication(applicationId);
        if (request.getExpiresAt() != null && !request.getExpiresAt().after(new Date())) {
            throw new ServiceException("Credential expiration time must be in the future.");
        }
        String secret = OpenApiSecretGenerator.clientSecret();
        OpenApiCredential credential = new OpenApiCredential();
        credential.setApplicationId(applicationId);
        credential.setCredentialName(request.getCredentialName().trim());
        credential.setClientId(uniqueClientId());
        credential.setSecretHash(BCrypt.hashpw(secret));
        credential.setSecretHint(secret.substring(secret.length() - 6));
        credential.setStatus(ACTIVE);
        credential.setExpiresAt(request.getExpiresAt());
        credentialMapper.insert(credential);
        return new OpenApiCredentialSecretResponse(credential.getId(), credential.getClientId(), secret, SECRET_WARNING);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OpenApiCredentialSecretResponse rotateCredential(Long applicationId, Long credentialId) {
        requireApplication(applicationId);
        OpenApiCredential credential = requireCredential(applicationId, credentialId);
        String secret = OpenApiSecretGenerator.clientSecret();
        credential.setSecretHash(BCrypt.hashpw(secret));
        credential.setSecretHint(secret.substring(secret.length() - 6));
        credential.setStatus(ACTIVE);
        if (credentialMapper.updateById(credential) != 1) {
            throw new ServiceException("Credential rotation conflict. Refresh and retry.");
        }
        return new OpenApiCredentialSecretResponse(credential.getId(), credential.getClientId(), secret, SECRET_WARNING);
    }

    @Override
    public void revokeCredential(Long applicationId, Long credentialId) {
        requireApplication(applicationId);
        OpenApiCredential credential = requireCredential(applicationId, credentialId);
        credential.setStatus(REVOKED);
        if (credentialMapper.updateById(credential) != 1) {
            throw new ServiceException("Credential revocation conflict. Refresh and retry.");
        }
    }

    private void validateRequest(OpenApiApplicationRequest request, Long excludeId) {
        String code = normalizeCode(request.getAppCode());
        if (!code.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new ServiceException("Application code may contain only letters, numbers, underscore and hyphen.");
        }
        long duplicate = applicationMapper.selectCount(new LambdaQueryWrapper<OpenApiApplication>()
            .eq(OpenApiApplication::getAppCode, code)
            .ne(excludeId != null, OpenApiApplication::getId, excludeId));
        if (duplicate > 0) {
            throw new ServiceException("Application code already exists.");
        }
        Set<String> requestedScopes = new LinkedHashSet<>(request.getScopes());
        if (!OpenApiScopeCatalog.ALL.containsAll(requestedScopes)) {
            requestedScopes.removeAll(OpenApiScopeCatalog.ALL);
            throw new ServiceException("Unsupported OpenAPI scope: " + String.join(",", requestedScopes));
        }
        Set<String> requestedEvents = new LinkedHashSet<>(request.getEventTypes());
        if (!OpenApiEventTypeCatalog.ALL.containsAll(requestedEvents)) {
            requestedEvents.removeAll(OpenApiEventTypeCatalog.ALL);
            throw new ServiceException("Unsupported OpenAPI event type: " + String.join(",", requestedEvents));
        }
        if (Boolean.TRUE.equals(request.getWebhookEnabled())) {
            if (StringUtils.isBlank(request.getWebhookUrl())) {
                throw new ServiceException("Webhook URL is required when Webhook is enabled.");
            }
            validateWebhookUrl(request.getWebhookUrl());
            boolean existingSecret = excludeId != null
                && StringUtils.isNotBlank(requireApplication(excludeId).getWebhookSecret());
            if (StringUtils.isBlank(request.getWebhookSecret()) && !existingSecret) {
                throw new ServiceException("Webhook signing secret is required when Webhook is enabled.");
            }
        }
        Set<String> cidrs = new LinkedHashSet<>();
        for (OpenApiIpRuleRequest rule : request.getIpRules()) {
            String normalized;
            try {
                normalized = IpCidrMatcher.normalize(rule.getCidr());
            } catch (IllegalArgumentException exception) {
                throw new ServiceException(exception.getMessage());
            }
            if (!cidrs.add(normalized)) {
                throw new ServiceException("Duplicate IP/CIDR rule: " + normalized);
            }
        }
    }

    private void validateWebhookUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("Webhook URL must be a valid http or https address.");
        }
    }

    private void fill(OpenApiApplication application, OpenApiApplicationRequest request) {
        application.setAppCode(normalizeCode(request.getAppCode()));
        application.setAppName(request.getAppName().trim());
        application.setEnabled(request.getEnabled() == null || request.getEnabled());
        application.setTokenTtlSeconds(request.getTokenTtlSeconds() == null ? 3600 : request.getTokenTtlSeconds());
        application.setRequestsPerMinute(request.getRequestsPerMinute() == null ? 120 : request.getRequestsPerMinute());
        application.setMaxConcurrentCalls(request.getMaxConcurrentCalls() == null ? 10 : request.getMaxConcurrentCalls());
        application.setWebsocketEnabled(request.getWebsocketEnabled() == null || request.getWebsocketEnabled());
        application.setWebhookEnabled(Boolean.TRUE.equals(request.getWebhookEnabled()));
        application.setWebhookUrl(StringUtils.trim(request.getWebhookUrl()));
        if (StringUtils.isNotBlank(request.getWebhookSecret())) {
            application.setWebhookSecret(request.getWebhookSecret().trim());
        }
        application.setSubscribedEvents(JsonUtils.toJsonString(request.getEventTypes().stream().distinct().sorted().toList()));
        application.setDescription(StringUtils.trim(request.getDescription()));
    }

    private void replacePolicies(Long applicationId, OpenApiApplicationRequest request) {
        deletePolicies(applicationId);
        request.getScopes().stream().map(String::trim).distinct().sorted().forEach(value -> {
            OpenApiApplicationScope scope = new OpenApiApplicationScope();
            scope.setApplicationId(applicationId);
            scope.setScopeCode(value);
            scopeMapper.insert(scope);
        });
        request.getIpRules().forEach(value -> {
            OpenApiIpRule rule = new OpenApiIpRule();
            rule.setApplicationId(applicationId);
            rule.setCidr(IpCidrMatcher.normalize(value.getCidr()));
            rule.setDescription(StringUtils.trim(value.getDescription()));
            rule.setEnabled(value.getEnabled() == null || value.getEnabled());
            ipRuleMapper.insert(rule);
        });
        request.getRoutePolicyCodes().stream().map(String::trim).filter(StringUtils::isNotBlank).distinct().forEach(value -> {
            OpenApiRouteGrant grant = new OpenApiRouteGrant();
            grant.setApplicationId(applicationId);
            grant.setRoutePolicyCode(value);
            grant.setEnabled(true);
            routeGrantMapper.insert(grant);
        });
    }

    private void deletePolicies(Long applicationId) {
        scopeMapper.delete(new LambdaQueryWrapper<OpenApiApplicationScope>().eq(OpenApiApplicationScope::getApplicationId, applicationId));
        ipRuleMapper.delete(new LambdaQueryWrapper<OpenApiIpRule>().eq(OpenApiIpRule::getApplicationId, applicationId));
        routeGrantMapper.delete(new LambdaQueryWrapper<OpenApiRouteGrant>().eq(OpenApiRouteGrant::getApplicationId, applicationId));
    }

    private OpenApiApplication requireApplication(Long id) {
        OpenApiApplication application = applicationMapper.selectById(id);
        if (application == null) {
            throw new ServiceException("OpenAPI application does not exist.");
        }
        return application;
    }

    private OpenApiCredential requireCredential(Long applicationId, Long credentialId) {
        OpenApiCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<OpenApiCredential>()
            .eq(OpenApiCredential::getId, credentialId)
            .eq(OpenApiCredential::getApplicationId, applicationId));
        if (credential == null) {
            throw new ServiceException("OpenAPI credential does not exist.");
        }
        return credential;
    }

    private String uniqueClientId() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String value = OpenApiSecretGenerator.clientId();
            long count = org.dromara.common.tenant.helper.TenantHelper.ignore(() -> credentialMapper.selectCount(
                new LambdaQueryWrapper<OpenApiCredential>().eq(OpenApiCredential::getClientId, value)));
            if (count == 0) {
                return value;
            }
        }
        throw new ServiceException("Unable to allocate a unique client ID.");
    }

    private OpenApiApplicationResponse response(OpenApiApplication application) {
        OpenApiApplicationResponse result = new OpenApiApplicationResponse();
        result.setId(application.getId());
        result.setAppCode(application.getAppCode());
        result.setAppName(application.getAppName());
        result.setEnabled(application.getEnabled());
        result.setTokenTtlSeconds(application.getTokenTtlSeconds());
        result.setRequestsPerMinute(application.getRequestsPerMinute());
        result.setMaxConcurrentCalls(application.getMaxConcurrentCalls());
        result.setWebsocketEnabled(application.getWebsocketEnabled());
        result.setWebhookEnabled(application.getWebhookEnabled());
        result.setWebhookUrl(application.getWebhookUrl());
        result.setWebhookSecretConfigured(StringUtils.isNotBlank(application.getWebhookSecret()));
        result.setEventTypes(StringUtils.isBlank(application.getSubscribedEvents())
            ? List.of() : JsonUtils.parseArray(application.getSubscribedEvents(), String.class));
        result.setDescription(application.getDescription());
        result.setVersion(application.getVersion());
        result.setCreateTime(application.getCreateTime());
        result.setScopes(scopeMapper.selectList(new LambdaQueryWrapper<OpenApiApplicationScope>()
                .eq(OpenApiApplicationScope::getApplicationId, application.getId())
                .orderByAsc(OpenApiApplicationScope::getScopeCode))
            .stream().map(OpenApiApplicationScope::getScopeCode).toList());
        result.setRoutePolicyCodes(routeGrantMapper.selectList(new LambdaQueryWrapper<OpenApiRouteGrant>()
                .eq(OpenApiRouteGrant::getApplicationId, application.getId())
                .eq(OpenApiRouteGrant::getEnabled, true)
                .orderByAsc(OpenApiRouteGrant::getRoutePolicyCode))
            .stream().map(OpenApiRouteGrant::getRoutePolicyCode).toList());
        List<OpenApiIpRuleResponse> rules = new ArrayList<>();
        for (OpenApiIpRule rule : ipRuleMapper.selectList(new LambdaQueryWrapper<OpenApiIpRule>()
            .eq(OpenApiIpRule::getApplicationId, application.getId()).orderByAsc(OpenApiIpRule::getCidr))) {
            OpenApiIpRuleResponse item = new OpenApiIpRuleResponse();
            item.setId(rule.getId());
            item.setCidr(rule.getCidr());
            item.setDescription(rule.getDescription());
            item.setEnabled(rule.getEnabled());
            rules.add(item);
        }
        result.setIpRules(rules);
        return result;
    }

    private OpenApiCredentialResponse credentialResponse(OpenApiCredential credential) {
        OpenApiCredentialResponse result = new OpenApiCredentialResponse();
        result.setId(credential.getId());
        result.setApplicationId(credential.getApplicationId());
        result.setCredentialName(credential.getCredentialName());
        result.setClientId(credential.getClientId());
        result.setSecretHint(credential.getSecretHint());
        result.setStatus(credential.getStatus());
        result.setExpiresAt(credential.getExpiresAt());
        result.setLastUsedAt(credential.getLastUsedAt());
        result.setCreateTime(credential.getCreateTime());
        return result;
    }

    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
