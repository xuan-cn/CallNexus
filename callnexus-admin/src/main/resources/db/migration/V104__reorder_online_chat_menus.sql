-- 在线客服：工作台是日常入口，渠道是配置页，工作台排在渠道之上。

UPDATE sys_menu SET order_num = 1 WHERE menu_id = '9501'; -- 在线客服工作台
UPDATE sys_menu SET order_num = 2 WHERE menu_id = '9500'; -- 在线客服渠道
