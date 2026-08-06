package org.dromara.openapi.service;

import org.dromara.openapi.domain.request.OpenApiApplicationRequest;
import org.dromara.openapi.domain.request.OpenApiCredentialRequest;
import org.dromara.openapi.domain.response.OpenApiApplicationResponse;
import org.dromara.openapi.domain.response.OpenApiCredentialResponse;
import org.dromara.openapi.domain.response.OpenApiCredentialSecretResponse;

import java.util.List;
import java.util.Set;

public interface OpenApiApplicationService {
    List<OpenApiApplicationResponse> list();
    OpenApiApplicationResponse get(Long id);
    Long create(OpenApiApplicationRequest request);
    void update(Long id, OpenApiApplicationRequest request);
    void delete(Long id);
    Set<String> availableScopes();
    List<OpenApiCredentialResponse> credentials(Long applicationId);
    OpenApiCredentialSecretResponse createCredential(Long applicationId, OpenApiCredentialRequest request);
    OpenApiCredentialSecretResponse rotateCredential(Long applicationId, Long credentialId);
    void revokeCredential(Long applicationId, Long credentialId);
}
