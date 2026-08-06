-- FAQ 编辑会创建不可变的新版本，同一相似问法允许在同一 FAQ 的不同版本中复用。
ALTER TABLE cc_ai_knowledge_faq_alias
    DROP INDEX uk_cc_ai_faq_alias_hash,
    ADD UNIQUE KEY uk_cc_ai_faq_alias_version_hash
        (tenant_id, faq_version_id, question_hash, deleted);
