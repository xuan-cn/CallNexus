-- 意图识别改为精确话术优先、Embedding + Qdrant 向量检索兜底。
-- 向量保存在按租户和 Embedding 模型隔离的 Qdrant Collection 中，不新增关系库字段。
ALTER TABLE cc_ai_intent_recognition_log
    MODIFY COLUMN match_method VARCHAR(16) NOT NULL COMMENT '匹配方式：EXACT、VECTOR、NONE',
    MODIFY COLUMN model_id BIGINT NULL COMMENT '向量识别使用的Embedding模型ID',
    MODIFY COLUMN raw_response MEDIUMTEXT NULL COMMENT '保留字段，向量识别不写入模型原始响应';
