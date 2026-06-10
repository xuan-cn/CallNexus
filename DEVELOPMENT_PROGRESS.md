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

- 已完成网关管理第一版，包括后端 CRUD、前端页面、菜单权限、数据库迁移脚本和动态 Gateway XML 后端输出。
- 已完成号码管理第一版，用于维护 DID、默认主叫号码、号码归属节点、呼入路由和启停状态。
- 已完成动态 Dialplan 第一版，支持 FreeSWITCH 通过独立 XML Curl 接口按号码获取呼入路由。
- 当前阻塞在服务器侧 FreeSWITCH 联调：手动 curl CallNexus `/dialplan` 可以返回正确 XML，但真实呼入时 FreeSWITCH 最新临时 XML 仍返回 `not found`，需要继续确认 `xml_curl.conf.xml` 的真实请求 URL、`domain`、`tenantId`、`destination_number` 和 `Caller-Context` 是否与手动 curl 一致。

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
- 已新增 FreeSWITCH 动态 Gateway XML 后端输出，支持 `mod_xml_curl` 通过 `purpose=gateways` 获取启用网关。
- 已将网关 XML 调整为 FreeSWITCH 期望的 user gateways 目录格式，`<gateway>` 位于 `<user><gateways>` 下。
- 已新增网关 `ping` 配置字段，默认 `0`，用于兼容运营商不响应 SIP OPTIONS 导致网关进入 `FAIL_WAIT` 的场景。
- 已实现网关新增、修改、删除后的 ESL 运行时同步能力，后续仍需在真实 FreeSWITCH 环境继续验证稳定性。

### 号码管理和动态 Dialplan

- 已新增号码管理数据表、后端 CRUD、前端页面、菜单和权限。
- 已支持号码绑定 FreeSWITCH 节点、网关、号码类型、默认主叫、呼入路由类型和呼入路由目标。
- 已实现独立动态 Dialplan XML 接口：

```text
POST /api/internal/freeswitch/dialplan
```

- 已保留原 `POST /api/internal/freeswitch/directory` 兼容能力，同时新增独立接口：

```text
POST /api/internal/freeswitch/directory/users
POST /api/internal/freeswitch/directory/gateways
```

- 已实现号码呼入到固定 SIP 分机的第一版路由，例如 DID `5295357` 可返回桥接到 `user/1001@192.168.244.128` 的 Dialplan XML。
- 已在 FreeSWITCH XML Curl 关键请求和返回位置补充中文日志，方便排查真实请求参数和路由命中情况。

### 文档

- 已新增 `FEATURE_ROADMAP.md`，记录全部功能模块和开发顺序。
- 已新增 `AI_DEVELOPMENT_GUIDE.md`，记录模块职责、AI 开发规范和后续设计。
- 已新增本文档 `DEVELOPMENT_PROGRESS.md`，用于开发交接和续开发。

## 阻塞事项

当前代码侧暂无已知编译阻塞，主要阻塞在 FreeSWITCH 服务器侧联调：

1. CallNexus 手动 curl `/api/internal/freeswitch/dialplan` 时可以返回正确 Dialplan XML，示例路由为 `5295357 -> user/1001@192.168.244.128`。
2. 真实外线呼入时，FreeSWITCH 生成的最新 `/tmp/*.tmp.xml` 仍可能是 `<result status="not found"/>`，说明真实 `mod_xml_curl` 请求的 URL 或参数仍与手动 curl 不一致。
3. 服务器侧 `xml_curl.conf.xml` 的 dialplan binding 必须使用独立地址 `/api/internal/freeswitch/dialplan`，并固定带上 `tenantId=000000` 和 `domain=192.168.244.128`；XML 中 URL 参数连接符必须写成 `&amp;`。
4. FreeSWITCH 配置里建议使用 `<param name="bindings" value="dialplan"/>`，directory/gateway binding 也统一使用 `bindings`。
5. 当前在部分 shell/container 中执行 `fs_cli -x ...` 报 `Error Connecting []`，说明未连到 FreeSWITCH Event Socket，暂时阻塞命令方式模拟呼入测试；需要进入真正运行 FreeSWITCH 的容器/主机，或使用正确的 `fs_cli -H 127.0.0.1 -P 8021 -p <password>` 参数。
6. 如果手机真实呼入无法触发正确 Dialplan，需要抓包或查看 CallNexus 后端中文日志，确认真实请求中的 `section`、`destination_number`、`Caller-Context`、`domain`、`tenantId`。

## 下一步开发任务

### 下一步 1：继续联调 FreeSWITCH 动态 Dialplan 真实呼入

状态：进行中，服务器侧请求参数阻塞

目标：

- 确认 FreeSWITCH 真实呼入时请求到 CallNexus 独立 `/dialplan` 接口。
- 确认真实请求带上正确 `tenantId`、`domain`、`destination_number` 和 `Caller-Context`。
- 确认真实呼入最新 `/tmp/*.tmp.xml` 返回 `section name="dialplan"`，而不是 `result not found`。

建议范围：

1. 检查服务器 `/etc/freeswitch/autoload_configs/xml_curl.conf.xml`。
2. 重载 `reloadxml` 和 `reload mod_xml_curl`。
3. 用 `curl`、`tcpdump`、FreeSWITCH 临时 XML 文件和 CallNexus 后端中文日志交叉确认真实请求参数。
4. 修复 `fs_cli` 连接问题后，用 `xml_locate` 或 `originate loopback/5295357/public` 模拟呼入。

本阶段暂不做：

- 不做 IVR。
- 不扩展队列、技能组和复杂路由。
- 不先开发 CDR，等呼入主链路打通后再做。

### 下一步 2：完善动态 Dialplan 和呼入路由

状态：第一版已完成，待真实呼入联调通过后继续

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
- 动态 Gateway XML 后端输出已完成，等待服务器侧 FreeSWITCH 配置联调。

### 已完成：号码管理和动态 Dialplan 第一版

完成结果：

- 号码管理后端、前端、菜单权限和数据库迁移已完成。
- 动态 XML Curl 接口已拆分为 users、gateways、dialplan 独立入口，并保留旧 directory 兼容入口。
- 动态 Dialplan 已支持按 DID 查询呼入路由，并生成桥接到 SIP 分机的 XML。
- 网关 XML 已支持 `ping=0` 配置，避免运营商不响应 OPTIONS 时频繁进入 `FAIL_WAIT`。
- 当前等待服务器侧 FreeSWITCH 真实呼入请求参数修正和联调验证。

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

### 2026-06-10：完成号码管理、动态 Dialplan 第一版，并记录 FreeSWITCH 联调阻塞

本次完成：

- 完成号码管理第一版，包括后端表结构、CRUD、前端页面、菜单权限和租户数据隔离。
- 完成动态 XML Curl 接口拆分，新增 `/api/internal/freeswitch/directory/users`、`/api/internal/freeswitch/directory/gateways`、`/api/internal/freeswitch/dialplan`，并保留旧 `/directory` 兼容入口。
- 完成动态 Gateway XML 模板调整，按 FreeSWITCH 期望输出 `domain name="all"`、`user id=网关名`、`user/gateways/gateway` 结构。
- 新增网关 `ping` 配置，默认 `0`，用于避免运营商线路不响应 OPTIONS 导致网关注册后进入 `FAIL_WAIT`。
- 完成动态 Dialplan 第一版，手动 curl 能返回 `5295357 -> user/1001@192.168.244.128` 的呼入桥接 XML。
- 补充 FreeSWITCH XML Curl 关键运行节点中文日志，后续排查真实请求时优先看后端日志中的 `section`、`purpose`、`domain`、`tenantId`、`destination_number` 和响应长度。

当前阻塞：

- CallNexus `/dialplan` 手动 curl 正常，但真实外线呼入时 FreeSWITCH 临时 XML 仍出现 `<result status="not found"/>`。
- 主要怀疑服务器侧 `xml_curl.conf.xml` dialplan binding 缺少固定 `domain=192.168.244.128`，或真实请求未进入独立 `/api/internal/freeswitch/dialplan`。
- 当前在某些容器/shell 中执行 `fs_cli -x ...` 报 `Error Connecting []`，无法直接用 `xml_locate` 或 `originate loopback` 模拟呼入，需要先修复 Event Socket 连接或进入正确 FreeSWITCH 容器。

下一步：

- 服务器侧修正并确认 `xml_curl.conf.xml`，dialplan binding 使用独立 `/dialplan` URL，并带 `token`、`tenantId=000000`、`domain=192.168.244.128`。
- 重新执行 `reloadxml` 和 `reload mod_xml_curl` 后，真实呼入并检查最新 `/tmp/*.tmp.xml` 是否返回 `section name="dialplan"`。
- 修复 `fs_cli` 连接后，使用 `xml_locate dialplan public 5295357` 或 `originate loopback/5295357/public` 做不依赖手机线路的命令测试。

### 2026-06-09：补齐 FreeSWITCH 动态 Gateway XML 后端输出

本次完成：

- `POST /api/internal/freeswitch/directory` 已支持识别 `purpose=gateways`。
- 新增网关内部查询服务，按租户和 FreeSWITCH 节点 SIP 域查询启用网关。
- 新增 Gateway XML 渲染器，当前按 FreeSWITCH 动态网关要求输出 `domain name="all"`，并将 `<gateway>` 放入 `<user><gateways>` 节点中。
- 动态 Gateway XML 使用内部 DTO 返回加密字段解密后的密码，仅用于 FreeSWITCH 内部 XML 接口，不暴露给后台管理详情接口。
- 抽象 FreeSWITCH XML Curl 分发结构，新增 `FreeSwitchXmlCurlDispatcher`、`FreeSwitchXmlCurlHandler`、`FreeSwitchXmlCurlRequest`，后续 dialplan、acl 等请求类型通过新增 Handler 扩展。
- 将普通 SIP Directory 用户 XML 和 Gateway XML 拆成不同 Renderer，避免不同 XML 模板混在同一个控制器中。
- `FreeSwitchDirectoryController` 已补充中文运行日志，记录 XML Curl 请求 section、purpose、domain、tenantId 和响应长度，不打印完整 XML 或敏感信息。
- 修复应用停止或重启阶段 ESL 监听线程池关闭后仍提交任务导致的 `RejectedExecutionException` 日志问题。
- 更新 `AI_DEVELOPMENT_GUIDE.md` 和 `CALL_CENTER_ENGINEERING_GUIDE.md`，明确后续关键运行节点必须补充中文日志，并禁止打印密码、token、完整 XML 和客户隐私。
- 已执行 `mvn -pl callnexus-admin -am -DskipTests compile`，后端编译通过。

后续仍需：

- 服务器侧配置 FreeSWITCH `mod_xml_curl` 和 external Sofia profile，验证是否能请求到 `purpose=gateways` 并完成网关注册。
- 联调动态 Gateway XML 时，重点确认 FreeSWITCH 是否请求 `section=directory&purpose=gateways`，以及是否识别 `domain name="all"` 下的 user gateway 配置。

下一步：

- 开发号码管理。
