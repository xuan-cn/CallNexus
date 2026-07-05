package org.dromara.ai.knowledge;

import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.*;

@Component
public class DocxDocumentParser implements KnowledgeDocumentParser {
    @Override public boolean supports(String suffix) { return "docx".equalsIgnoreCase(suffix); }
    @Override public ParsedDocument parse(InputStream input, String fileName) throws Exception {
        List<ParsedSection> sections = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(input)) {
            String title = fileName;
            StringBuilder text = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String value = paragraph.getText().trim();
                    if (value.isBlank()) continue;
                    if (paragraph.getStyle() != null && paragraph.getStyle().toLowerCase(Locale.ROOT).startsWith("heading")) {
                        append(sections, title, text); title = value;
                    } else text.append(value).append('\n');
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        text.append(row.getTableCells().stream().map(XWPFTableCell::getText).map(String::trim)
                            .reduce((a, b) -> a + " | " + b).orElse("")).append('\n');
                    }
                }
            }
            append(sections, title, text);
        }
        return new ParsedDocument(sections, 1);
    }
    private void append(List<ParsedSection> target, String title, StringBuilder text) {
        String value = text.toString().trim();
        if (!value.isBlank()) target.add(new ParsedSection(title, null, null, null, null, value));
        text.setLength(0);
    }
}
