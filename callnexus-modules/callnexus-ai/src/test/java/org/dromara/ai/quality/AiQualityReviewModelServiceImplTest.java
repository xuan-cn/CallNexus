package org.dromara.ai.quality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("dev")
class AiQualityReviewModelServiceImplTest {

    @Test
    void keepsExactEvidenceQuote() {
        assertEquals("请问有什么需要", AiQualityReviewModelServiceImpl.resolveEvidenceQuote(
            "您好，请问有什么需要帮助？", "请问有什么需要"));
    }

    @Test
    void mapsPunctuationAndWhitespaceDifferencesBackToOriginalText() {
        assertEquals("您好， 我们是客服", AiQualityReviewModelServiceImpl.resolveEvidenceQuote(
            "您好， 我们是客服。", "您好我们是客服"));
    }

    @Test
    void rejectsParaphrasedOrCrossSegmentEvidence() {
        assertNull(AiQualityReviewModelServiceImpl.resolveEvidenceQuote(
            "请您稍后再试。", "稍后工作人员会联系您"));
    }
}
