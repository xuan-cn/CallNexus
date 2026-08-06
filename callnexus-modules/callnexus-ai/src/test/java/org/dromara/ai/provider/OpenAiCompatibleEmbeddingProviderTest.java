package org.dromara.ai.provider;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleEmbeddingProviderTest {

    @Test
    void shouldRetryTransientGatewayFailures() {
        assertThat(OpenAiCompatibleEmbeddingProvider.isRetryableGatewayFailure(
            new ServiceException("Embedding 模型调用失败，HTTP状态码=503，响应=upstream reset"))).isTrue();
        assertThat(OpenAiCompatibleEmbeddingProvider.isRetryableGatewayFailure(
            new ServiceException("Embedding 模型调用失败，HTTP状态码=504，响应=timeout"))).isTrue();
    }

    @Test
    void shouldNotRetryConfigurationOrAuthenticationFailures() {
        assertThat(OpenAiCompatibleEmbeddingProvider.isRetryableGatewayFailure(
            new ServiceException("Embedding 模型调用失败，HTTP状态码=400，响应=invalid model"))).isFalse();
        assertThat(OpenAiCompatibleEmbeddingProvider.isRetryableGatewayFailure(
            new ServiceException("Embedding 模型调用失败，HTTP状态码=401，响应=unauthorized"))).isFalse();
    }
}
