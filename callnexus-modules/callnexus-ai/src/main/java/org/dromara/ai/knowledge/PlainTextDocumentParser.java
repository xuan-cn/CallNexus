package org.dromara.ai.knowledge;

import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class PlainTextDocumentParser implements KnowledgeDocumentParser {
    @Override public boolean supports(String suffix) { return "txt".equalsIgnoreCase(suffix) || "md".equalsIgnoreCase(suffix); }
    @Override public ParsedDocument parse(InputStream input, String fileName) throws Exception {
        String content = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<ParsedSection> sections = new ArrayList<>();
        String title = fileName;
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n")) {
            if (line.matches("^#{1,6}\\s+.+")) {
                append(sections, title, current);
                title = line.replaceFirst("^#{1,6}\\s+", "").trim();
            } else {
                current.append(line).append('\n');
            }
        }
        append(sections, title, current);
        if (sections.isEmpty() && !content.isBlank()) sections.add(new ParsedSection(fileName, null, null, null, null, content.trim()));
        return new ParsedDocument(sections, 1);
    }
    private void append(List<ParsedSection> target, String title, StringBuilder text) {
        String value = text.toString().trim();
        if (!value.isBlank()) target.add(new ParsedSection(title, null, null, null, null, value));
        text.setLength(0);
    }
}
