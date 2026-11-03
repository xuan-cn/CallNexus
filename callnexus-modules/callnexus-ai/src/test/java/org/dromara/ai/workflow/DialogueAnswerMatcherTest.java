package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@Tag("dev")
class DialogueAnswerMatcherTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiIntentApplicationService intents = mock(AiIntentApplicationService.class);
    private final DialogueAnswerMatcher matcher = new DialogueAnswerMatcher(intents);
    private ObjectNode config() {
        ObjectNode config = json.createObjectNode();
        config.put("acceptedAnswers", "需要").put("declinedAnswers", "不需要");
        return config;
    }
    @Test void exactIgnoresPunctuationButDoesNotUseSubstring() {
        assertEquals("DECLINED", matcher.match(config(), " 不需要。", 1L).branch());
        assertNull(matcher.match(config(), "我不需要这个服务", 1L).branch());
        verifyNoInteractions(intents);
    }
    @Test void keywordsAnyAndExclusions() {
        var config = config().put("declinedMatchMode", "KEYWORD").put("declinedAnswers", "不需要\n不用").put("declinedExcludedKeywords", "不是不需要\n不需要吗");
        assertEquals("KEYWORD_MATCH", matcher.match(config, "我不用了", 1L).reason());
        assertNull(matcher.match(config, "不是不需要", 1L).branch());
        assertNull(matcher.match(config, "不需要吗？", 1L).branch());
    }
    @Test void keywordsAllRequireEveryWord() {
        var config = config().put("acceptedMatchMode", "KEYWORD").put("acceptedKeywordMode", "ALL").put("acceptedAnswers", "申请\n服务");
        assertNull(matcher.match(config, "我要申请", 1L).branch());
        assertEquals("COMPLETE", matcher.match(config, "请申请这个服务", 1L).branch());
    }
    @Test void conflictingBranchesDoNotPickFirst() {
        var config = config().put("declinedMatchMode", "KEYWORD").put("declinedAnswers", "需要");
        assertEquals("MATCH_CONFLICT", matcher.match(config, "需要", 1L).reason());
        assertEquals(java.util.List.of("COMPLETE", "DECLINED"), matcher.match(config, "需要", 1L).matchedBranches());
        assertNull(matcher.match(config, "需要", 1L).branch());
    }
    @Test void optionsUseIndependentModesAndSkipConfirmation() {
        var config = config();
        config.putArray("answerOptions").addObject().put("code", "FAMILY").put("matchMode", "KEYWORD").put("answers", "爸爸\n父亲");
        assertEquals("OPTION_FAMILY", matcher.match(config, "我是他爸爸", 1L).branch());
        assertNull(matcher.match(config, "需要", 1L).branch());
    }
    @Test void intentRecognitionIsRestrictedToConfiguredCodes() {
        var config = config().put("declinedMatchMode", "INTENT");
        config.putArray("declinedIntentCodes").add("DECLINE");
        var result = mock(AiIntentRecognitionResponse.class);
        when(result.isMatched()).thenReturn(true);
        when(result.getIntentCode()).thenReturn("DECLINE");
        when(result.getConfidence()).thenReturn(new java.math.BigDecimal("0.93"));
        when(intents.recognize(any())).thenReturn(result);
        assertEquals("DECLINED", matcher.match(config, "暂时用不上", 1L).branch());
        verify(intents).recognize(argThat((AiIntentRecognitionRequest r) -> r.getIntentCodes().equals(java.util.List.of("DECLINE"))));
        assertEquals(.93, matcher.match(config, "暂时用不上", 1L).confidence());
        assertNotNull(matcher.match(config, "暂时用不上", 1L).intentMs());
        when(result.getIntentCode()).thenReturn("UNCONFIGURED");
        assertNull(matcher.match(config, "暂时用不上", 1L).branch());
    }
    @Test void exactMatchSkipsIntentRequest() {
        var config = config().put("declinedMatchMode", "INTENT");
        config.putArray("declinedIntentCodes").add("DECLINE");
        assertEquals("COMPLETE", matcher.match(config, "需要", 1L).branch());
        verifyNoInteractions(intents);
    }
    @Test void intentFailureDoesNotAdvance() {
        var config = config().put("declinedMatchMode", "INTENT");
        config.putArray("declinedIntentCodes").add("DECLINE");
        when(intents.recognize(any())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals("INTENT_FAILED", matcher.match(config, "暂时用不上", 1L).reason());
    }
    @Test void validatesModesAndRequiredInputs() {
        assertThrows(ServiceException.class, () -> DialogueAnswerMatcher.validate(config().put("acceptedMatchMode", "UNKNOWN")));
        assertThrows(ServiceException.class, () -> DialogueAnswerMatcher.validate(config().put("declinedMatchMode", "INTENT")));
        assertThrows(ServiceException.class, () -> DialogueAnswerMatcher.validate(config().put("declinedMatchMode", "KEYWORD").put("declinedAnswers", "")));
    }
    @Test void sameIntentOnMultipleBranchesIsConflict() {
        var config = config().put("declinedMatchMode", "INTENT");
        config.putArray("declinedIntentCodes").add("FAMILY");
        config.putArray("answerOptions").addObject().put("code", "FAMILY").put("matchMode", "INTENT").putArray("intentCodes").add("FAMILY");
        var result = mock(AiIntentRecognitionResponse.class);
        when(result.isMatched()).thenReturn(true);
        when(result.getIntentCode()).thenReturn("FAMILY");
        when(intents.recognize(any())).thenReturn(result);
        assertEquals("MATCH_CONFLICT", matcher.match(config, "我是他爸爸", 1L).reason());
    }
}
