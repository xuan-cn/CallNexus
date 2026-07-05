package org.dromara.ai.provider;

public interface EmbeddingProvider {
    String providerType();
    EmbeddingResult embed(EmbeddingRequest request);
}
