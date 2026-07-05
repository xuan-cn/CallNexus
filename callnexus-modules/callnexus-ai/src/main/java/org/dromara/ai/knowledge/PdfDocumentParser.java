package org.dromara.ai.knowledge;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.*;

@Component
public class PdfDocumentParser implements KnowledgeDocumentParser {
    @Override public boolean supports(String suffix) { return "pdf".equalsIgnoreCase(suffix); }
    @Override public ParsedDocument parse(InputStream input, String fileName) throws Exception {
        try (PDDocument document = Loader.loadPDF(input.readAllBytes())) {
            List<ParsedSection> sections = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = stripper.getText(document).trim();
                if (!text.isBlank()) sections.add(new ParsedSection(fileName, page, null, null, null, text));
            }
            return new ParsedDocument(sections, document.getNumberOfPages());
        }
    }
}
