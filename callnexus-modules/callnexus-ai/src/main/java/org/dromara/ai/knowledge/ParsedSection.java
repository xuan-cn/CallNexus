package org.dromara.ai.knowledge;

public record ParsedSection(String titlePath, Integer pageNumber, String sheetName,
                            Integer rowStart, Integer rowEnd, String text) {}
