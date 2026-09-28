-- Add an optional control-and-resume policy without changing existing assistants.
SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cc_ai_agent' AND COLUMN_NAME = 'barge_in_strategy'),
    'SELECT 1',
    'ALTER TABLE cc_ai_agent ADD COLUMN barge_in_strategy VARCHAR(24) NOT NULL DEFAULT ''INTERRUPT'' COMMENT ''Barge-in action: INTERRUPT or CONTROL_RESUME'' AFTER barge_in_grace_ms'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cc_ai_agent' AND COLUMN_NAME = 'barge_in_control_text'),
    'SELECT 1',
    'ALTER TABLE cc_ai_agent ADD COLUMN barge_in_control_text VARCHAR(500) NULL COMMENT ''Speech played before resuming interrupted output'' AFTER barge_in_strategy'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cc_ai_agent' AND COLUMN_NAME = 'barge_in_control_max_count'),
    'SELECT 1',
    'ALTER TABLE cc_ai_agent ADD COLUMN barge_in_control_max_count INT NOT NULL DEFAULT 2 COMMENT ''Maximum control responses per call'' AFTER barge_in_control_text'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cc_ai_agent' AND COLUMN_NAME = 'barge_in_control_cooldown_ms'),
    'SELECT 1',
    'ALTER TABLE cc_ai_agent ADD COLUMN barge_in_control_cooldown_ms INT NOT NULL DEFAULT 5000 COMMENT ''Cooldown after a control response in milliseconds'' AFTER barge_in_control_max_count'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
