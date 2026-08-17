package org.dromara.customer.customer.domain.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class CustomerImportAnalysisResponse {
    private String fileName;
    private int totalRows;
    private List<Column> columns = new ArrayList<>();
    private List<Map<String, String>> sampleRows = new ArrayList<>();

    @Data
    public static class Column {
        private String header;
        private String suggestedField;
    }
}
