package org.dromara.ai.knowledge;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class KnowledgeDocumentParserRegistry {
    private final List<KnowledgeDocumentParser> parsers;
    public KnowledgeDocumentParserRegistry(List<KnowledgeDocumentParser> parsers) { this.parsers = parsers; }
    public KnowledgeDocumentParser get(String suffix) {
        return parsers.stream().filter(item -> item.supports(suffix)).findFirst()
            .orElseThrow(() -> new ServiceException("暂不支持该知识文档类型：" + suffix));
    }
}
