-- 兼容早期已成功创建 row_number 字段的数据库。
-- 新数据库通过 V29 直接创建 source_row_number，本迁移不执行任何结构变更。
SET @has_old_row_number = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'cc_outbound_import_row'
      AND COLUMN_NAME = 'row_number'
);

SET @rename_sql = IF(
    @has_old_row_number > 0,
    'ALTER TABLE cc_outbound_import_row DROP INDEX idx_cc_outbound_import_row_batch, CHANGE COLUMN `row_number` source_row_number INT NOT NULL COMMENT ''原始表格行号'', ADD KEY idx_cc_outbound_import_row_batch (tenant_id, batch_id, source_row_number)',
    'SELECT 1'
);

PREPARE rename_statement FROM @rename_sql;
EXECUTE rename_statement;
DEALLOCATE PREPARE rename_statement;
