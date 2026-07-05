package org.dromara.ai.knowledge;

import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按配置长度切分知识文档，并尽量在自然文本边界结束切片。
 */
@Component
public class KnowledgeTextSplitter {

    public List<KnowledgeChunkDraft> split(ParsedDocument document, int chunkSize, int overlap) {
        int size = Math.max(100, chunkSize);
        int safeOverlap = Math.max(0, Math.min(overlap, size / 2 - 1));
        List<SectionGroup> groups = groups(document.sections());
        List<KnowledgeChunkDraft> result = new ArrayList<>();
        int index = 0;
        for (SectionGroup group : groups) {
            String text = normalize(group.text());
            int offset = 0;
            while (offset < text.length()) {
                int end = Math.min(text.length(), offset + size);
                if (end < text.length()) {
                    end = preferredBoundary(text, offset, end, size);
                }
                String content = text.substring(offset, end).trim();
                if (content.length() >= 20) {
                    result.add(new KnowledgeChunkDraft(index++, group.titlePath(), group.pageNumber(),
                        group.sheetName(), group.rowStart(), group.rowEnd(), content));
                }
                if (end >= text.length()) {
                    break;
                }
                offset = Math.max(offset + 1, end - safeOverlap);
            }
        }
        return result;
    }

    private List<SectionGroup> groups(List<ParsedSection> sections) {
        List<SectionGroup> result = new ArrayList<>();
        SectionGroupBuilder textGroup = null;
        for (ParsedSection section : sections) {
            if (StringUtils.isBlank(section.text())) {
                continue;
            }
            // PDF 不跨页、Excel 不跨 Sheet。TXT、Markdown 和 DOCX 的标题章节连续组合，
            // 让前端配置的最大分段长度真正决定切片大小。
            if (section.pageNumber() != null || StringUtils.isNotBlank(section.sheetName())) {
                if (textGroup != null) {
                    result.add(textGroup.build());
                    textGroup = null;
                }
                result.add(new SectionGroup(section.titlePath(), section.pageNumber(), section.sheetName(),
                    section.rowStart(), section.rowEnd(), sectionText(section)));
                continue;
            }
            if (textGroup == null) {
                textGroup = new SectionGroupBuilder(section);
            } else {
                textGroup.append(section);
            }
        }
        if (textGroup != null) {
            result.add(textGroup.build());
        }
        return result;
    }

    private int preferredBoundary(String text, int start, int end, int size) {
        int minimum = Math.min(end, start + Math.max(100, size * 3 / 4));
        for (int i = end; i > minimum; i--) {
            char value = text.charAt(i - 1);
            if (value == '\n' || value == '\u3002' || value == '\uff01' || value == '\uff1f'
                || value == '\uff1b' || value == ';' || value == '!' || value == '?') {
                return i;
            }
        }
        return end;
    }

    private String sectionText(ParsedSection section) {
        if (StringUtils.isBlank(section.titlePath())) {
            return section.text();
        }
        return "## " + section.titlePath().trim() + "\n" + section.text();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').replaceAll("[ \\t]+", " ")
            .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private record SectionGroup(String titlePath, Integer pageNumber, String sheetName,
                                Integer rowStart, Integer rowEnd, String text) {
    }

    private static final class SectionGroupBuilder {
        private final String firstTitle;
        private final StringBuilder text = new StringBuilder();

        private SectionGroupBuilder(ParsedSection section) {
            this.firstTitle = section.titlePath();
            append(section);
        }

        private void append(ParsedSection section) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            if (StringUtils.isNotBlank(section.titlePath())) {
                text.append("## ").append(section.titlePath().trim()).append('\n');
            }
            text.append(section.text().trim());
        }

        private SectionGroup build() {
            return new SectionGroup(firstTitle, null, null, null, null, text.toString());
        }
    }
}
