package org.dromara.ai.provider;

import java.util.function.Consumer;

public interface ChatProvider {
    String providerType();
    ChatResult chat(ChatRequest request);
    ChatResult stream(ChatRequest request, Consumer<String> deltaConsumer);
}
