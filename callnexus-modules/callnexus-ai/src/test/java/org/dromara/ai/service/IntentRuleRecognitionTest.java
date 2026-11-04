package org.dromara.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.EmbeddingProviderRegistry;
import org.dromara.ai.service.impl.AiIntentApplicationServiceImpl;
import org.dromara.ai.vector.VectorStore;
import org.dromara.common.core.utils.SpringUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@Tag("dev")
class IntentRuleRecognitionTest {
    @Test void negativeWinsAndRulesNeedNoEmbedding() throws Exception { run(true, false); }
    @Test void multiplePositiveIntentsReportConflict() throws Exception { run(false, true); }
    @Test void onePositiveIntentMatches() throws Exception { run(false, false); }
    private void run(boolean negative, boolean conflict) throws Exception {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.refresh();
            new SpringUtils().setApplicationContext(context);
            var intents = mock(AiIntentMapper.class); var utterances = mock(AiIntentUtteranceMapper.class);
            var bindings = mock(AiAgentIntentMapper.class); var logs = mock(AiIntentRecognitionLogMapper.class);
            var agents = mock(AiAgentMapper.class); var models = mock(AiModelMapper.class);
            var providers = mock(AiModelProviderMapper.class); var embeddings = mock(EmbeddingProviderRegistry.class);
            var vectors = mock(VectorStore.class);
            var agent = new AiAgent(); agent.setId(1L); agent.setEnabled(true);
            when(agents.selectById(1L)).thenReturn(agent);
            List<AiIntent> values = new ArrayList<>(); List<AiAgentIntent> links = new ArrayList<>();
            for (long id = 1; id <= (conflict ? 2 : 1); id++) {
                var intent = new AiIntent(); intent.setId(id); intent.setEnabled(true); intent.setIntentName("家属" + id); intent.setIntentCode("FAMILY" + id);
                intent.setMatchRulesJson("[{\"polarity\":\"POSITIVE\",\"mode\":\"ANY\",\"groups\":[[\"爸爸\"]]}" + (negative ? ",{\"polarity\":\"NEGATIVE\",\"mode\":\"ANY\",\"groups\":[[\"爸爸\"]]}" : "") + "]");
                values.add(intent); var binding = new AiAgentIntent(); binding.setIntentId(id); links.add(binding);
            }
            when(bindings.selectList(any())).thenReturn(links);
            when(intents.selectBatchIds(any())).thenReturn(values);
            when(utterances.selectList(any())).thenReturn(List.of());
            var service = new AiIntentApplicationServiceImpl(intents, mock(AiIntentGroupMapper.class), utterances, bindings, logs, agents, models, providers, embeddings, vectors);
            var request = new AiIntentRecognitionRequest(); request.setAgentId(1L); request.setText("我是他爸爸");
            var result = service.recognize(request);
            assertThat(result.isMatched()).isEqualTo(!negative && !conflict);
            if (conflict) assertThat(result.getMatchMethod()).isEqualTo("RULE_CONFLICT");
            if (negative) assertThat(result.getReason()).contains("反例", "爸爸");
            if (!negative && !conflict) assertThat(result.getReason()).contains("正例", "爸爸");
            verifyNoInteractions(models, providers, embeddings, vectors);
            verify(logs).insert(any(AiIntentRecognitionLog.class));
        }
    }
}
