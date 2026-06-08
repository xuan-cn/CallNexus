# CallNexus 开发进度交接文档

本文档用于记录 CallNexus 当前开发到哪里、下一步从哪里继续，以及每次开发完成后必须如何更新进度。后续 Claude Code、Codex 或其他 AI 接手开发时，应优先阅读本文档，再阅读 `FEATURE_ROADMAP.md`、`AI_DEVELOPMENT_GUIDE.md`、`CALL_CENTER_ENGINEERING_GUIDE.md`。

## 使用规则

每次开始开发前：

1. 先阅读本文档，确认“当前开发位置”和“下一步开发任务”。
2. 再阅读 `FEATURE_ROADMAP.md`，确认模块优先级和整体路线。
3. 再阅读 `AI_DEVELOPMENT_GUIDE.md` 和 `CALL_CENTER_ENGINEERING_GUIDE.md`，确认模块边界和代码规范。
4. 本次只开发“下一步开发任务”中明确的内容，不做无关重构。

每次开发完成后，必须更新本文档：

1. 更新“当前开发位置”。
2. 更新“已完成内容”。
3. 更新“下一步开发任务”。
4. 更新“阻塞事项”。
5. 在“开发记录”追加一条记录。
6. 同步更新 `FEATURE_ROADMAP.md` 中对应模块状态。

## 当前开发位置

当前处于：

**阶段 3：网关、号码和呼入路由基础能力开发中**

基础呼叫中心底座已经基本完成，FreeSWITCH 动态 Directory 已完成联调，当前进入外线接入前置能力建设。

当前正在处理的问题：

- 已完成网关管理第一版，包括后端 CRUD、前端页面、菜单权限和数据库迁移脚本。
- 下一步需要开发号码管理，用于维护 DID、主叫号码、号码归属租户和启停状态。
- 号码管理完成后，再开发动态 Dialplan 和呼入路由。

## 已完成内容

### 基础平台

- 后台登录已可用。
- 首页已从介绍页调整为呼叫中心运营首页。
- 左侧菜单和顶部布局已适配呼叫中心系统。

### 坐席和 SIP 基础能力

- 已实现 FreeSWITCH 节点管理。
- 已实现 SIP 账号管理。
- 已实现坐席管理。
- 已支持坐席绑定系统用户、SIP 账号、FreeSWITCH 节点。
- 已支持坐席签入、签出、示忙、示闲。
- 已支持坐席电话条展示基础状态。

### 呼叫控制

- 已接入 ESL 基础连接。
- 已实现 ESL 命令调用基础能力。
- 已支持通过浏览器坐席工作台触发普通 SIP 软电话拨打。
- 已处理部分通话挂断后浏览器状态同步问题。
- 当前 WebRTC WSS 直连浏览器软电话方案暂不作为主方案，优先采用“普通 SIP 软电话 + ESL 控制”。
- 已处理软电话侧挂断后 Web 电话条仍显示通话中的状态同步问题。

### FreeSWITCH 动态 XML

- 已实现后端动态 Directory 接口：

```text
POST /api/internal/freeswitch/directory
```

- 支持 token：

```text
CALLNEXUS_FREESWITCH_DIRECTORY_SECRET
```

- 默认 token：

```text
cnx_fs_dir_8f3d9c2b7a1e4f6a9c0d5b2e
```

- 支持默认租户：

```text
CALLNEXUS_FREESWITCH_DIRECTORY_TENANT_ID=000000
```

- 接口用于 FreeSWITCH `mod_xml_curl` 请求，不是普通浏览器 GET 接口。
- 已完成 `mod_xml_curl` 联调验证。
- 已验证后台新增 SIP 账号可以通过 FreeSWITCH 动态 Directory 注册。
- 已兼容 FreeSWITCH 注册时传入 IP 域名的场景，Directory 查询先按 `domain + extension` 查，查不到再按分机号兜底。

示例测试命令：

```bash
curl -X POST "http://后端地址/api/internal/freeswitch/directory?token=cnx_fs_dir_8f3d9c2b7a1e4f6a9c0d5b2e&tenantId=000000" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "section=directory&domain=freeswitch.local&user=1003"
```

### 客户和工单

- 已实现客户管理。
- 已实现工单管理。
- 已实现动态表单模板。
- 已支持客户和工单自定义字段。
- 已支持单选、多选、下拉等选项标签和值分离。
- 已支持字段布局：一行两个字段或独占整行。
- 已支持客户详情和工单详情展示自定义字段。
- 已支持客户跟进记录。
- 已支持客户详情右侧 Tabs，当前第一个 Tab 为跟进记录，后续可扩展通话记录。
- 新建客户时，如果号码已存在，应直接带出已有客户信息，不重复创建。

### 网关和外线接入基础

- 已实现 FreeSWITCH 网关管理后端 CRUD。
- 已新增 `cc_freeswitch_gateway` 数据表。
- 已新增网关管理菜单和权限。
- 已新增前端网关管理页面。
- 当前网关管理只负责后台配置维护，尚未生成 FreeSWITCH Gateway XML。

### 文档

- 已新增 `FEATURE_ROADMAP.md`，记录全部功能模块和开发顺序。
- 已新增 `AI_DEVELOPMENT_GUIDE.md`，记录模块职责、AI 开发规范和后续设计。
- 已新增本文档 `DEVELOPMENT_PROGRESS.md`，用于开发交接和续开发。

## 阻塞事项

当前暂无代码侧阻塞。

服务器侧后续仍需要配合的事项：

1. 动态 Gateway XML 开发完成后，需要配置 FreeSWITCH 通过 CallNexus 获取网关 XML。
2. 动态 Dialplan 开发完成后，需要配置 FreeSWITCH 通过 CallNexus 获取拨号计划 XML。

## 下一步开发任务

### 下一步 1：开发号码管理

状态：待开始

目标：

- 维护 DID、主叫号码、号码归属租户、启停状态。
- 为呼入路由和呼出路由提供基础数据。

建议范围：

1. 后端号码表。
2. 号码 CRUD 接口。
3. 前端号码管理页面。
4. 菜单和权限。
5. 数据库迁移脚本。

本阶段暂不做：

- 不做呼入路由。
- 不做动态 Dialplan。
- 不做 IVR。

### 下一步 2：开发动态 Dialplan 和呼入路由

状态：待开始

目标：

- FreeSWITCH 根据 CallNexus 的路由配置动态生成拨号计划。
- 外线来电可以按号码进入 IVR、队列、坐席或技能组。

### 下一步 3：开发 CDR 通话记录

状态：待开始

目标：

- 将 ESL 事件和 FreeSWITCH 通话生命周期整理为可查询的通话记录。
- 为客户详情、工单详情的通话记录 Tab 做准备。

## 已完成阶段

### 已完成：FreeSWITCH 动态 Directory 联调

完成结果：

- FreeSWITCH 已配置 `mod_xml_curl`。
- FreeSWITCH 日志已能看到请求 CallNexus Directory 接口。
- CallNexus 后端已能返回正确 Directory XML。
- 后台新增 SIP 账号后，普通 SIP 软电话可以注册成功。

### 已完成：网关管理第一版

完成结果：

- 后端网关表、CRUD 接口已完成。
- 前端网关管理页面已完成。
- 菜单和权限已完成。
- 数据库迁移脚本已完成。
- 暂未生成 FreeSWITCH Gateway XML。

## 开发记录

### 2026-06-08：新增开发进度交接文档

本次新增：

- 新增 `DEVELOPMENT_PROGRESS.md`。
- 明确当前开发位置为“阶段 2：FreeSWITCH 动态配置接入中”。
- 明确下一步先完成 FreeSWITCH 动态 Directory 联调。
- 明确 Directory 联调完成后进入网关管理。

后续接手人必须在每次开发完成后追加记录，并更新当前开发位置和下一步任务。

### 2026-06-08：完成 FreeSWITCH 动态 Directory 联调和网关管理第一版

本次完成：

- FreeSWITCH 动态 Directory 已完成联调，后台新增 SIP 账号可以通过 FreeSWITCH 注册。
- 修复 Directory 表单请求参数读取问题。
- 兼容 FreeSWITCH 注册时传入 IP 域名的场景。
- 修复软电话挂断后 Web 电话条仍显示通话中的问题。
- 新增 FreeSWITCH 网关管理后端 CRUD。
- 新增 `cc_freeswitch_gateway` 表。
- 新增网关管理前端页面。
- 新增网关管理菜单和权限。

下一步：

- 开发号码管理。
