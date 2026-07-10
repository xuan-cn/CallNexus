package org.dromara.ai.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式文本分句器（线程不安全，每个会话请单独持有一个实例）。
 *
 * 分句策略：
 *  1) 缓冲区累积到达 MIN_LENGTH 前，不切分（避免过短片段）。
 *  2) 遇到句末标点（。！？；\n.!?;）立即切一句。
 *  3) 长度 >= MAX_LENGTH 且遇到次级标点（，、,:：）时切一句。
 *  4) 长度 >= HARD_MAX 时无论有无标点强制切一句（防超长）。
 */
public class SentenceSegmenter {

    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 40;
    private static final int HARD_MAX = 80;

    private static final String PRIMARY_STOPS = "。！？；\n.!?;";
    private static final String SECONDARY_STOPS = "，,:：";

    private final StringBuilder buffer = new StringBuilder();

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
        if (len >= MAX_LENGTH) {
            for (int i = MIN_LENGTH - 1; i < len; i++) {
                char c = buffer.charAt(i);
                if (SECONDARY_STOPS.indexOf(c) >= 0) {
                    return i;
                }
            }
        }
        if (len >= HARD_MAX) {
            return HARD_MAX - 1;
        }
        return -1;
    }
}
