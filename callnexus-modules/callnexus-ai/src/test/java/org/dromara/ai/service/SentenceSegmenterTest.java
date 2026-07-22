package org.dromara.ai.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentenceSegmenterTest {

    @Test
    void shouldEmitFirstSentenceImmediatelyAtPrimaryStop() {
        SentenceSegmenter segmenter = new SentenceSegmenter();

        assertEquals(List.of("您好，请问需要什么帮助？"), segmenter.append("您好，请问需要什么帮助？"));
    }

    @Test
    void shouldUseShorterHardLimitForFirstSegment() {
        SentenceSegmenter segmenter = new SentenceSegmenter();
        String text = "这是一段没有任何标点并且需要尽快开始播放的电话回复内容";

        List<String> segments = segmenter.append(text);

        assertEquals(1, segments.size());
        assertEquals(24, segments.get(0).length());
    }

    @Test
    void shouldPreferCommaAfterFirstSoftLimit() {
        SentenceSegmenter segmenter = new SentenceSegmenter();

        List<String> segments = segmenter.append("我已经查询到您的预约信息，现在为您确认具体时间，后面还有其他说明");

        assertEquals(List.of("我已经查询到您的预约信息，"), segments);
    }

    @Test
    void shouldKeepFollowupSegmentLongerThanFirstSegment() {
        SentenceSegmenter segmenter = new SentenceSegmenter();
        String first = "第一句话已经结束。";
        assertEquals(List.of(first), segmenter.append(first));

        String followup = "后续内容没有标点时应当保留更长一些从而避免电话播报被切得过于零碎并保持自然";
        List<String> segments = segmenter.append(followup);

        assertTrue(segments.isEmpty());
        assertEquals(followup, segmenter.drain());
    }
}
