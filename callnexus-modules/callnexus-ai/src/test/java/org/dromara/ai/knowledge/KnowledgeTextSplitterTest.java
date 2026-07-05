package org.dromara.ai.knowledge;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeTextSplitterTest {

    private final KnowledgeTextSplitter splitter = new KnowledgeTextSplitter();

    @Test
    void shouldKeepSourceAndOverlapChunks() {
        String text = "第一段知识。".repeat(80) + "第二段知识。".repeat(80);
        ParsedDocument document = new ParsedDocument(List.of(new ParsedSection("标题", 3, null, null, null, text)), 3);

        List<KnowledgeChunkDraft> chunks = splitter.split(document, 300, 60);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.pageNumber()).isEqualTo(3);
            assertThat(chunk.titlePath()).isEqualTo("标题");
            assertThat(chunk.content()).isNotBlank();
        });
        assertThat(chunks.get(0).content().length()).isLessThanOrEqualTo(300);
    }

    @Test
    void shouldHonorConfiguredSizeAcrossShortTitleSections() {
        ParsedDocument document = new ParsedDocument(List.of(
            new ParsedSection("第一节", null, null, null, null, "第一节内容。".repeat(30)),
            new ParsedSection("第二节", null, null, null, null, "第二节内容。".repeat(30)),
            new ParsedSection("第三节", null, null, null, null, "第三节内容。".repeat(30))
        ), 1);

        List<KnowledgeChunkDraft> chunks300 = splitter.split(document, 300, 60);
        List<KnowledgeChunkDraft> chunks800 = splitter.split(document, 800, 100);

        assertThat(chunks300.size()).isGreaterThan(chunks800.size());
        assertThat(chunks300).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(300));
        assertThat(chunks800).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(800));
    }

    @Test
    void shouldNormalizeFaqQuestionStably() {
        assertThat(KnowledgeTextUtils.normalizeQuestion("  如何  重置，密码？ "))
            .isEqualTo(KnowledgeTextUtils.normalizeQuestion("如何重置密码"));
    }
}
