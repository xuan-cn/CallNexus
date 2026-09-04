package org.dromara.ai.provider;

import org.dromara.ai.domain.AiModelProvider;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleEmbeddingProviderTest {

    @Test
    void shouldReuseOnlyRecentlyBorrowedClientForSameUpstream() {
        AiModelProvider provider = provider("https://embedding.example.com", 10);

        HttpClient first = OpenAiCompatibleSupport.client(provider, 0L);
        HttpClient reused = OpenAiCompatibleSupport.client(provider,
            OpenAiCompatibleSupport.CLIENT_IDLE_REUSE_NANOS);
        HttpClient rotated = OpenAiCompatibleSupport.client(provider,
            OpenAiCompatibleSupport.CLIENT_IDLE_REUSE_NANOS * 2 + 1L);

        assertThat(reused).isSameAs(first);
        assertThat(rotated).isNotSameAs(first);
    }

    @Test
    void shouldIsolateClientPoolsByUpstreamAddress() {
        HttpClient first = OpenAiCompatibleSupport.client(provider("https://embedding-a.example.com", 10), 0L);
        HttpClient second = OpenAiCompatibleSupport.client(provider("https://embedding-b.example.com", 10), 0L);

        assertThat(second).isNotSameAs(first);
    }

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

    @Test
    void shouldExtractUpstreamBatchLimit() {
        ServiceException exception = new ServiceException("Embedding 模型调用失败，HTTP状态码=400，响应={\"error\":{\"message\":\"Value error, batch size is invalid, it should not be larger than 10.: input.contents\"}}");

        assertThat(OpenAiCompatibleEmbeddingProvider.extractBatchLimit(exception)).isEqualTo(10);
    }

    @Test
    void shouldIgnoreUnrelatedBadRequest() {
        ServiceException exception = new ServiceException("Embedding 模型调用失败，HTTP状态码=400，响应=invalid model");

        assertThat(OpenAiCompatibleEmbeddingProvider.extractBatchLimit(exception)).isNull();
    }

    private AiModelProvider provider(String baseUrl, int connectTimeoutSeconds) {
        AiModelProvider provider = new AiModelProvider();
        provider.setBaseUrl(baseUrl);
        provider.setConnectTimeoutSeconds(connectTimeoutSeconds);
        return provider;
    }
}
