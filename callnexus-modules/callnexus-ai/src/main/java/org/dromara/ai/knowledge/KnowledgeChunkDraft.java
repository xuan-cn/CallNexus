package org.dromara.ai.knowledge;

public record KnowledgeChunkDraft(int index, String titlePath, Integer pageNumber, String sheetName,
                                  Integer rowStart, Integer rowEnd, String content) {}
