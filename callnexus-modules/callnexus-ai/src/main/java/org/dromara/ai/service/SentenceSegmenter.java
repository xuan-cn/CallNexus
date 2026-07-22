package org.dromara.ai.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式文本分句器（线程不安全，每个会话请单独持有一个实例）。
 *
 * 分句策略：
 *  1) 缓冲区累积到达 MIN_LENGTH 前，不切分（避免过短片段）。
 *  2) 遇到句末标点（。！？；\n.!?;）立即切一句。
 *  3) 首段采用更短阈值，让电话用户尽快听到回复；后续段使用较长阈值保持自然度。
 *  4) 达到软阈值后优先在次级标点（，、,:：）切分，达到硬阈值后强制切分。
 */
public class SentenceSegmenter {

    private static final int MIN_LENGTH = 4;
    private static final int FIRST_SOFT_MAX = 12;
    private static final int FIRST_HARD_MAX = 24;
    private static final int FOLLOWUP_SOFT_MAX = 24;
    private static final int FOLLOWUP_HARD_MAX = 48;

    private static final String PRIMARY_STOPS = "。！？；\n.!?;";
    private static final String SECONDARY_STOPS = "，,:：";

    private final StringBuilder buffer = new StringBuilder();
    private boolean firstSegment = true;

    /**
     * 追加新增文本，返回可以立即发送的完整句子列表（可能为 0 到 N 个）。
     */
    public List<String> append(String delta) {
        List<String> out = new ArrayList<>();
        if (delta == null || delta.isEmpty()) {
            return out;
        }
        buffer.append(delta);
        while (true) {
            int cut = findCutIndex();
            if (cut < 0) {
                break;
            }
            String sentence = buffer.substring(0, cut + 1).trim();
            buffer.delete(0, cut + 1);
            if (!sentence.isEmpty()) {
                out.add(sentence);
                firstSegment = false;
            }
        }
        return out;
    }

    /**
     * LLM 流结束时调用，返回缓冲区残留（若有）。
     */
    public String drain() {
        String rest = buffer.toString().trim();
        buffer.setLength(0);
        return rest.isEmpty() ? null : rest;
    }

    private int findCutIndex() {
        int len = buffer.length();
        if (len < MIN_LENGTH) {
            return -1;
        }
        for (int i = 0; i < len; i++) {
            char c = buffer.charAt(i);
            if (PRIMARY_STOPS.indexOf(c) >= 0 && i + 1 >= MIN_LENGTH) {
                return i;
            }
        }
        int softMax = firstSegment ? FIRST_SOFT_MAX : FOLLOWUP_SOFT_MAX;
        int hardMax = firstSegment ? FIRST_HARD_MAX : FOLLOWUP_HARD_MAX;
        if (len >= softMax) {
            for (int i = softMax - 1; i < len; i++) {
                char c = buffer.charAt(i);
                if (SECONDARY_STOPS.indexOf(c) >= 0) {
                    return i;
                }
            }
        }
        if (len >= hardMax) {
            return hardMax - 1;
        }
        return -1;
    }
}
