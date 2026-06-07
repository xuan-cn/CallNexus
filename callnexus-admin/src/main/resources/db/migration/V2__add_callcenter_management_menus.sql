-- 呼叫中心一级菜单
insert into sys_menu values('9000', '呼叫中心', '0', '1', 'callcenter', null, '', 1, 0, 'M', '0', '0', '', 'phone', 103, 1, sysdate(), null, null, '呼叫中心管理目录');

-- 呼叫中心管理页面
insert into sys_menu values('9001', 'SIP账号管理', '9000', '1', 'sip-account', 'callcenter/sip-account/index', '', 1, 0, 'C', '0', '0', 'callcenter:sip-account:list', 'phone', 103, 1, sysdate(), null, null, 'SIP账号管理菜单');
insert into sys_menu values('9002', '坐席管理', '9000', '2', 'agent', 'callcenter/agent/index', '', 1, 0, 'C', '0', '0', 'callcenter:agent:list', 'user', 103, 1, sysdate(), null, null, '坐席管理菜单');

-- SIP账号按钮权限
insert into sys_menu values('9011', 'SIP账号查询', '9001', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:sip-account:query', '#', 103, 1, sysdate(), null, null, '');
insert into sys_menu values('9012', 'SIP账号新增', '9001', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:sip-account:create', '#', 103, 1, sysdate(), null, null, '');
insert into sys_menu values('9013', 'SIP账号修改', '9001', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:sip-account:update', '#', 103, 1, sysdate(), null, null, '');
insert into sys_menu values('9014', 'SIP账号删除', '9001', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:sip-account:delete', '#', 103, 1, sysdate(), null, null, '');

-- 坐席按钮权限
insert into sys_menu values('9021', '坐席查询', '9002', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:agent:query', '#', 103, 1, sysdate(), null, null, '');
insert into sys_menu values('9022', '坐席新增', '9002', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:agent:create', '#', 103, 1, sysdate(), null, null, '');
insert into sys_menu values('9023', '坐席修改', '9002', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:agent:update', '#', 103, 1, sysdate(), null, null, '');
insert into sys_menu values('9024', '坐席删除', '9002', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:agent:delete', '#', 103, 1, sysdate(), null, null, '');
insert into sys_menu values('9025', '坐席绑定分机', '9002', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:agent:bind-extension', '#', 103, 1, sysdate(), null, null, '');
