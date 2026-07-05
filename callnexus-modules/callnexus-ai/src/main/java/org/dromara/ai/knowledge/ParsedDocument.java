package org.dromara.ai.knowledge;

import java.util.List;

public record ParsedDocument(List<ParsedSection> sections, int pageCount) {
    public int characterCount() {
        return sections.stream().mapToInt(item -> item.text() == null ? 0 : item.text().length()).sum();
    }
}
