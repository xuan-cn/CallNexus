# 外呼黑名单任务选择权限修复

## 原因

- 黑名单页面初始化同时请求黑名单和旧 `/api/v1/outbound-tasks`。
- 旧接口要求 `callcenter:outbound-task:list`，且只查询 PREVIEW 任务，并不是自动外呼任务接口。
- V157 已将原外呼任务菜单改为点击拨号，菜单权限改为 `callcenter:click-dial:list`。给当前菜单授权不会授予旧权限。
- 原页面使用 Promise.all，任务接口失败时，黑名单查询即使成功也不会填入表格。

## 修复

- 黑名单列表仅请求 `/api/v1/outbound-blacklists`，要求 `callcenter:outbound-blacklist:list`。
- 新增、编辑、导入打开时读取 `/api/v1/outbound-blacklists/task-options`。
- 选项接口允许 create/update/import 任一黑名单权限，仅返回当前租户未删除 AUTO 任务的 id、taskCode、taskName。
- 沿用现有 MyBatis 租户及逻辑删除过滤，不更改原任务接口的权限，不返回任务配置或客户数据。
- 无需恢复旧菜单或修改数据库；需同时更新前后端。

## 验证

1. 普通角色只授予黑名单菜单查询权限，不授予点击拨号或自动外呼权限；重新登录后查看列表，不应请求旧任务接口。
2. 增加黑名单新增权限，打开新增并选择指定任务，应显示 AUTO 任务，保存后刷新成功。
3. 分别验证编辑及 Excel 导入的任务选择，保持原对应操作权限。
4. 没有黑名单新增/修改/导入权限的账号不能访问 task-options；不增加旧 outbound-tasks 接口访问权限。

上述为部署后的人工验收步骤，单元测试不替代真实账号及租户隔离验收。
