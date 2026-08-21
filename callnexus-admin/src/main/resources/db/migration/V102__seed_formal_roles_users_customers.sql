-- 演示用正式角色 / 用户 / 客户种子（可重复执行，INSERT IGNORE）。
-- Flyway 默认关闭时，可手工执行本脚本。

-- ========== 1. 角色 ==========
-- data_scope: 1全部 3本部门 4本部门及以下 5仅本人
INSERT IGNORE INTO sys_role (
    role_id, tenant_id, role_name, role_key, role_sort, data_scope,
    menu_check_strictly, dept_check_strictly, status, del_flag,
    create_dept, create_by, create_time, update_by, update_time, remark
) VALUES
(10, '000000', '呼叫中心主管', 'cc_supervisor', 10, '1', 1, 1, '0', '0', 103, 1, SYSDATE(), NULL, NULL, '客户/工单/任务/呼叫运营全权限'),
(11, '000000', '客服坐席',     'cc_agent',      11, '5', 1, 1, '0', '0', 103, 1, SYSDATE(), NULL, NULL, '来电弹屏、客户与工单处理、通话记录'),
(12, '000000', '质检班长',     'cc_qa',         12, '1', 1, 1, '0', '0', 103, 1, SYSDATE(), NULL, NULL, '通话质检、客户与工单只读查看');

-- 把原先偏测试的角色名称改得更正式
UPDATE sys_role
SET role_name = '部门经理',
    remark = '数据范围：本部门及以下'
WHERE role_id = 3 AND role_key = 'test1';

UPDATE sys_role
SET role_name = '普通员工',
    remark = '数据范围：仅本人'
WHERE role_id = 4 AND role_key = 'test2';

UPDATE sys_role
SET role_name = '客服（全功能）',
    remark = '历史客服角色，含呼叫中心大部分菜单'
WHERE role_id = 2063520745744551937 AND role_key = 'dispatch';

-- ========== 2. 角色菜单权限 ==========
-- 客户管理目录 + 列表 + 新增
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(10, 2083002869484634113), (10, 9004), (10, 9490),
(11, 2083002869484634113), (11, 9004), (11, 9490),
(12, 2083002869484634113), (12, 9004);

-- 工单管理目录 + 列表 + 新增（质检只看列表）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(10, 2083003767048912898), (10, 9005), (10, 9491),
(11, 2083003767048912898), (11, 9005), (11, 9491),
(12, 2083003767048912898), (12, 9005);

-- 任务管理（主管全开；坐席仅资料分配查询）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(10, 9600), (10, 9601), (10, 9602),
(10, 9610), (10, 9611), (10, 9612), (10, 9613), (10, 9614), (10, 9615),
(10, 9620), (10, 9621),
(11, 9600), (11, 9602), (11, 9620);

-- 呼叫运营（主管全开；坐席：通话记录+外呼执行；质检：通话记录）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(10, 9800), (10, 9009), (10, 9091),
(10, 9150), (10, 9151), (10, 9152), (10, 9153), (10, 9154), (10, 9155),
(10, 9160), (10, 9145), (10, 9260), (10, 9300),
(11, 9800), (11, 9009), (11, 9091),
(11, 9150), (11, 9151), (11, 9155),
(12, 9800), (12, 9009), (12, 9091);

-- 在线客服工作台（坐席/主管）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(10, 9510), (10, 9501), (10, 9506), (10, 9507), (10, 9508),
(11, 9510), (11, 9501), (11, 9506), (11, 9507), (11, 9508);

-- 报表目录（占位，主管可见）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(10, 9700), (12, 9700);

-- ========== 3. 演示用户（密码均为 666666）==========
INSERT IGNORE INTO sys_user (
    user_id, tenant_id, dept_id, user_name, nick_name, user_type,
    email, phonenumber, sex, avatar, password, status, del_flag,
    login_ip, login_date, create_dept, create_by, create_time, update_by, update_time, remark
) VALUES
(101, '000000', 103, 'supervisor_li', '李敏', 'sys_user', 'li.min@demo.local', '13810001001', '1', NULL,
 '$2a$10$b8yUzN0C71sbz.PhNOCgJe.Tu1yWC3RNrTyjSQ8p1W0.aaUXUJ.Ne',
 '0', '0', '127.0.0.1', SYSDATE(), 103, 1, SYSDATE(), NULL, NULL, '演示-呼叫中心主管'),
(102, '000000', 103, 'agent_zhang', '张伟', 'sys_user', 'zhang.wei@demo.local', '13810001002', '0', NULL,
 '$2a$10$b8yUzN0C71sbz.PhNOCgJe.Tu1yWC3RNrTyjSQ8p1W0.aaUXUJ.Ne',
 '0', '0', '127.0.0.1', SYSDATE(), 103, 1, SYSDATE(), NULL, NULL, '演示-客服坐席'),
(103, '000000', 103, 'qa_wang', '王芳', 'sys_user', 'wang.fang@demo.local', '13810001003', '1', NULL,
 '$2a$10$b8yUzN0C71sbz.PhNOCgJe.Tu1yWC3RNrTyjSQ8p1W0.aaUXUJ.Ne',
 '0', '0', '127.0.0.1', SYSDATE(), 103, 1, SYSDATE(), NULL, NULL, '演示-质检班长');

INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES
(101, 10),
(102, 11),
(103, 12);

-- ========== 4. 正式样例客户 ==========
INSERT IGNORE INTO cc_customer (
    id, tenant_id, primary_phone, customer_name, template_id, source_call_id,
    create_dept, create_by, create_time, update_by, update_time, version, deleted
) VALUES
(9200001, '000000', '13980001001', '华创科技-陈志远', NULL, NULL, 103, 1, SYSDATE(), NULL, NULL, 0, 0),
(9200002, '000000', '13980001002', '锦程贸易-刘雅婷', NULL, NULL, 103, 1, SYSDATE(), NULL, NULL, 0, 0),
(9200003, '000000', '13980001003', '星河金融-周明轩', NULL, NULL, 103, 1, SYSDATE(), NULL, NULL, 0, 0),
(9200004, '000000', '13980001004', '青云教育-吴思涵', NULL, NULL, 103, 1, SYSDATE(), NULL, NULL, 0, 0),
(9200005, '000000', '13980001005', '盛达物流-赵国强', NULL, NULL, 103, 1, SYSDATE(), NULL, NULL, 0, 0),
(9200006, '000000', '13980001006', '安泰医疗-孙晓梅', NULL, NULL, 103, 1, SYSDATE(), NULL, NULL, 0, 0),
(9200007, '000000', '13980001007', '恒信地产-郑海涛', NULL, NULL, 103, 1, SYSDATE(), NULL, NULL, 0, 0),
(9200008, '000000', '13980001008', '博雅咨询-何嘉怡', NULL, NULL, 103, 1, SYSDATE(), NULL, NULL, 0, 0);

INSERT IGNORE INTO cc_customer_phone (
    id, tenant_id, customer_id, phone_number, normalized_phone, phone_type,
    phone_label, primary_flag, enabled, sort_order,
    create_dept, create_by, create_time, update_by, update_time, version
) VALUES
(9201001, '000000', 9200001, '13980001001', '13980001001', 'MOBILE', '主号码', 1, 1, 0, 103, 1, SYSDATE(), NULL, NULL, 0),
(9201002, '000000', 9200002, '13980001002', '13980001002', 'MOBILE', '主号码', 1, 1, 0, 103, 1, SYSDATE(), NULL, NULL, 0),
(9201003, '000000', 9200003, '13980001003', '13980001003', 'MOBILE', '主号码', 1, 1, 0, 103, 1, SYSDATE(), NULL, NULL, 0),
(9201004, '000000', 9200004, '13980001004', '13980001004', 'MOBILE', '主号码', 1, 1, 0, 103, 1, SYSDATE(), NULL, NULL, 0),
(9201005, '000000', 9200005, '13980001005', '13980001005', 'MOBILE', '主号码', 1, 1, 0, 103, 1, SYSDATE(), NULL, NULL, 0),
(9201006, '000000', 9200006, '13980001006', '13980001006', 'MOBILE', '主号码', 1, 1, 0, 103, 1, SYSDATE(), NULL, NULL, 0),
(9201007, '000000', 9200007, '13980001007', '13980001007', 'MOBILE', '主号码', 1, 1, 0, 103, 1, SYSDATE(), NULL, NULL, 0),
(9201008, '000000', 9200008, '13980001008', '13980001008', 'MOBILE', '主号码', 1, 1, 0, 103, 1, SYSDATE(), NULL, NULL, 0);
