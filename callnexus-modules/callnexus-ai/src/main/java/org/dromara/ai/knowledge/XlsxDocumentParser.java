package org.dromara.ai.knowledge;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.*;

@Component
public class XlsxDocumentParser implements KnowledgeDocumentParser {
    @Override public boolean supports(String suffix) { return "xlsx".equalsIgnoreCase(suffix); }
    @Override public ParsedDocument parse(InputStream input, String fileName) throws Exception {
        List<ParsedSection> sections = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(input)) {
            for (Sheet sheet : workbook) {
                StringBuilder text = new StringBuilder();
                int start = -1, end = -1;
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell).trim();
                        if (!value.isBlank()) cells.add(value);
                    }
                    if (!cells.isEmpty()) {
                        if (start < 0) start = row.getRowNum() + 1;
                        end = row.getRowNum() + 1;
                        text.append(String.join(" | ", cells)).append('\n');
                    }
                }
                if (!text.isEmpty()) sections.add(new ParsedSection(sheet.getSheetName(), null, sheet.getSheetName(), start, end, text.toString().trim()));
            }
            return new ParsedDocument(sections, workbook.getNumberOfSheets());
        }
    }
}
