package org.dromara.openapi.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.openapi.domain.request.OpenApiApplicationRequest;
import org.dromara.openapi.domain.request.OpenApiCredentialRequest;
import org.dromara.openapi.domain.response.OpenApiApplicationResponse;
import org.dromara.openapi.domain.response.OpenApiCredentialResponse;
import org.dromara.openapi.domain.response.OpenApiCredentialSecretResponse;
import org.dromara.openapi.service.OpenApiApplicationService;
import org.dromara.openapi.event.OpenApiEventTypeCatalog;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/openapi/applications")
@RequiredArgsConstructor
public class OpenApiApplicationController {
    private final OpenApiApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:openapi-application:list")
    public R<List<OpenApiApplicationResponse>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:openapi-application:query")
    public R<OpenApiApplicationResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @GetMapping("/available-scopes")
    @SaCheckPermission("callcenter:openapi-application:list")
    public R<Set<String>> availableScopes() {
        return R.ok(service.availableScopes());
    }

    @GetMapping("/available-events")
    @SaCheckPermission("callcenter:openapi-application:list")
    public R<Set<String>> availableEvents() {
        return R.ok(OpenApiEventTypeCatalog.ordered());
    }

    @PostMapping
    @SaCheckPermission("callcenter:openapi-application:create")
    public R<Long> create(@Valid @RequestBody OpenApiApplicationRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:openapi-application:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody OpenApiApplicationRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:openapi-application:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @GetMapping("/{id}/credentials")
    @SaCheckPermission("callcenter:openapi-application:credential")
    public R<List<OpenApiCredentialResponse>> credentials(@PathVariable Long id) {
        return R.ok(service.credentials(id));
    }

    @PostMapping("/{id}/credentials")
    @SaCheckPermission("callcenter:openapi-application:credential")
    public R<OpenApiCredentialSecretResponse> createCredential(@PathVariable Long id,
                                                                @Valid @RequestBody OpenApiCredentialRequest request) {
        return R.ok(service.createCredential(id, request));
    }

    @PostMapping("/{id}/credentials/{credentialId}/rotate")
    @SaCheckPermission("callcenter:openapi-application:credential")
    public R<OpenApiCredentialSecretResponse> rotateCredential(@PathVariable Long id,
                                                                @PathVariable Long credentialId) {
        return R.ok(service.rotateCredential(id, credentialId));
    }

    @PostMapping("/{id}/credentials/{credentialId}/revoke")
    @SaCheckPermission("callcenter:openapi-application:credential")
    public R<Void> revokeCredential(@PathVariable Long id, @PathVariable Long credentialId) {
        service.revokeCredential(id, credentialId);
        return R.ok();
    }
}
