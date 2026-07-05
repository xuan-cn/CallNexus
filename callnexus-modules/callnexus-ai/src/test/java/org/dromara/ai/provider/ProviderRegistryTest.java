package org.dromara.ai.provider;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRegistryTest {

    @Test
    void duplicateChatProviderTypeShouldFailAtStartup() {
        ChatProvider first = provider("OPENAI_COMPATIBLE");
        ChatProvider second = provider("openai_compatible");

        assertThatThrownBy(() -> new ChatProviderRegistry(List.of(first, second)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("重复的 Chat Provider 类型");
    }

    private ChatProvider provider(String type) {
        return new ChatProvider() {
            @Override public String providerType() { return type; }
            @Override public ChatResult chat(ChatRequest request) { return null; }
            @Override public ChatResult stream(ChatRequest request, java.util.function.Consumer<String> consumer) { return null; }
        };
    }
}
