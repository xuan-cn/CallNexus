package org.dromara.ai.knowledge;

import java.io.InputStream;

public interface KnowledgeDocumentParser {
    boolean supports(String suffix);
    ParsedDocument parse(InputStream input, String fileName) throws Exception;
}
