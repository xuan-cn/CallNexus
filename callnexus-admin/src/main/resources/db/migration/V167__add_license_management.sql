CREATE TABLE IF NOT EXISTS cc_license_import (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    license_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    license_file MEDIUMBLOB NOT NULL,
    imported_by BIGINT NOT NULL,
    imported_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

SET @license_parent = (SELECT menu_id FROM sys_menu WHERE parent_id = 0 AND path = 'system' AND menu_type = 'M' LIMIT 1);
SET @license_menu = (SELECT COALESCE(MAX(menu_id), 10000) + 1 FROM sys_menu);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
    menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
SELECT @license_menu, '授权管理', @license_parent, 98, 'license', 'system/license/index', 1, 1,
    'C', '0', '0', 'system:license:manage', 'system', 103, 1, SYSDATE(), '平台超级管理员离线授权管理'
WHERE @license_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'system/license/index');
