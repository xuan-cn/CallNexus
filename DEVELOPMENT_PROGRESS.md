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

**阶段 4：真实呼入闭环与 CDR 通话记录联调中**

基础呼叫中心底座、FreeSWITCH 动态 Directory、网关、号码管理和固定分机呼入路由已经完成第一版，真实外线已经可以呼入，当前重点是完成浏览器坐席工作台闭环并验证 CDR 数据。

当前正在处理的问题：

- 已完成网关管理第一版，包括后端 CRUD、前端页面、菜单权限、数据库迁移脚本和动态 Gateway XML 后端输出。
- 已完成号码管理第一版，用于维护 DID、默认主叫号码、号码归属节点、呼入路由和启停状态。
- 已完成动态 Dialplan 第一版，支持 FreeSWITCH 通过独立 XML Curl 接口按号码获取呼入路由。
- 已增强真实呼入兼容能力：支持从更多 FreeSWITCH 请求字段提取被叫号码，并兼容 `sip:`、`tel:`、`号码@域名` 等格式；请求 domain 无法匹配节点时，可按租户内唯一 DID 兜底查找绑定节点。
- 已完成真实外线呼入验证：将网关 `ping` 设置为 `0` 后可以正常拨入，确认该运营商线路不适合使用 SIP OPTIONS 探测。
- 已完成 CDR 通话记录第一版，支持 ESL 通话生命周期落库、列表详情查询，以及客户和工单详情的通话记录 Tab。
- 已支持浏览器软电话通过活动通话快照恢复来电状态，避免遗漏 WebSocket 瞬时事件后无法自动弹出。
- 浏览器悬浮软电话已支持拖拽、边界限制和当前会话位置记忆。

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
- 当前坐席接口已返回活动通话快照，浏览器通过 WebSocket 实时事件与定时同步双重机制恢复来电号码、通话 ID 和软电话面板状态。
- 悬浮软电话支持拖拽标题栏移动，避免遮挡客户或工单操作按钮。

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
- 客户详情和工单详情的通话记录 Tab 已接入真实 CDR 查询。
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
- 已修正网关运行态同步语义：新增执行 `reloadxml + rescan`；修改执行 `killgw + 等待释放 + reloadxml + rescan`；删除仅执行 `killgw`，避免旧配置不生效或删除后被重新加载。
- 已补齐网关 REGISTER 续期、注册重试、Ping 阈值、From 头、Contact 参数、Context 和 Extension 等可配置参数。
- 网关页面的“注册与保活”和“高级配置”已改为折叠展示。

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

### CDR 通话记录

- 已新增 `cc_call_record` 通话记录表、菜单、后端查询接口、前端列表和详情页面。
- 已支持 `CHANNEL_CREATE`、振铃、接听、桥接和挂断事件持续更新通话记录。
- 已记录 Channel UUID、Call UUID、主叫、被叫、坐席分机、呼叫方向、开始时间、接听时间、结束时间、总时长、通话时长和挂断原因。
- 当前按每个 FreeSWITCH Channel UUID 保存一条底层通话腿记录，因此一次外线呼入桥接到坐席时可能产生两条记录。
- 后续需要将同一业务通话的多条通话腿聚合为列表中的一条业务通话，详情中再展示底层通话腿。

### 文档

- 已新增 `FEATURE_ROADMAP.md`，记录全部功能模块和开发顺序。
- 已新增 `AI_DEVELOPMENT_GUIDE.md`，记录模块职责、AI 开发规范和后续设计。
- 已新增本文档 `DEVELOPMENT_PROGRESS.md`，用于开发交接和续开发。

## 阻塞事项

当前代码侧暂无已知编译阻塞。FreeSWITCH 服务器侧联调结论：

1. 网关 `5295357` 在 `ping > 0` 时无法正常呼入，将 `ping` 改为 `0` 后已可正常拨入。
2. 该运营商线路不适合通过 SIP OPTIONS 判断网关健康状态，后续必须保持 `ping=0`。
3. 网关健康状态应优先依据注册状态、真实呼叫结果和告警策略判断。
4. 如果后续再次出现无法呼入，优先检查网关是否被误改为 `ping > 0`，再检查注册状态、SIP INVITE 和动态 Dialplan。
5. 当前 CDR 按底层通话腿记录，一次呼入可能产生“外线到 DID”和“桥接到坐席”两条记录，这是当前第一版设计，待继续实现业务通话聚合。
6. 浏览器来电自动弹出需要后端活动通话快照和前端新版本同时部署，并保持坐席已签入。

## 下一步开发任务

### 下一步 1：部署并验证真实呼入浏览器闭环

状态：进行中，代码已完成，待部署联调

目标：

- 确认外线呼入后目标软电话正常响铃、接听和双向通话。
- 确认浏览器坐席工作台能展示真实来电号码。
- 确认软电话挂断后浏览器状态自动结束。
- 确认呼入过程中可以创建或带出客户、创建工单。
- 确认遗漏 WebSocket 来电事件时，浏览器最多 3 秒内仍可通过活动通话快照自动弹出软电话。
- 确认悬浮软电话拖拽、窗口边界限制和位置记忆正常。

建议范围：

1. 使用手机拨入线路号码并接听。
2. 验证双向语音和 RTP NAT。
3. 检查 CallNexus 浏览器来电事件和客户号码。
4. 完整验证接听、挂断、客户和工单操作。

### 下一步 2：实现 CDR 业务通话聚合

状态：第一版底层通话腿记录已完成，待开发聚合

目标：

- 使用 `callUuid`、桥接 UUID 和关联 UUID 将同一次呼入的 A-Leg、B-Leg 聚合为一条业务通话。
- 通话记录列表只展示一条业务记录，例如“客户号码 -> DID -> 坐席 1001”。
- 通话记录详情展示全部底层通话腿。
- 明确业务通话方向、最终坐席、接听结果和通话时长计算规则。

### 下一步 3：接入录音和显式业务关联

状态：待 CDR 聚合完成后开始

目标：

- 将录音文件与业务通话关联并支持回放。
- 支持通话记录显式关联客户和工单，不再只依赖电话号码查询。
- 完成以上能力后，再进入 IVR、技能组和队列开发。

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

### 2026-06-11：完成 CDR 通话记录第一版

本次完成：

- 新增 `cc_call_record` 通话记录表和通话记录菜单。
- ESL 通话事件已接入 CDR 生命周期落库，支持创建、振铃、接听、桥接和挂断状态。
- 通话挂断后记录总时长、接通时长和 FreeSWITCH 挂断原因。
- 支持按主叫、被叫、参与号码、呼叫方向和通话状态查询。
- 新增通话记录列表与详情页面。
- 客户详情和工单详情的“通话记录”Tab 已接入真实 CDR 数据。
- CDR 落库异常已与实时电话条状态处理隔离，数据库写入失败不会阻断浏览器通话状态推送。

下一步：

- 部署并执行 `V13__add_call_record.sql`，通过真实呼入、呼出和内部通话验证 CDR 数据。
- 根据真实 FreeSWITCH 事件头优化多通话腿聚合规则，再接入录音文件和客户、工单显式关联。

### 2026-06-11：增强浏览器来电恢复与悬浮软电话交互

本次完成：

- 修复客户外线呼入时浏览器可能未自动弹出软电话的问题。
- 将活动通话快照补充到当前坐席接口，返回活动通话 ID 和对端号码。
- 前端继续使用 WebSocket 实时处理来电，同时每 3 秒同步当前坐席活动通话作为兜底。
- 页面刷新、WebSocket 重连或来电事件遗漏时，浏览器可恢复活动通话并自动展开软电话。
- 悬浮软电话支持通过标题栏拖拽，位置被限制在浏览器可视区域，并在当前会话中记忆。
- 后端 Maven 编译通过，相关前端 ESLint 通过。

下一步：

- 部署前后端新版本，验证真实外线呼入时浏览器自动弹出、号码展示、拖拽、接听、挂断和状态恢复。
- 开发 CDR 业务通话聚合，将同一次呼入产生的多条底层通话腿合并为一条业务通话记录。

### 2026-06-11：补齐网关注册保活和高级参数

本次完成：

- 网关新增 REGISTER 续期和失败重试配置，默认值分别为 `expire-seconds=60`、`retry-seconds=30`。
- 网关新增 `ping-max`、`ping-min` 配置，仅在启用 OPTIONS ping 时输出。
- 网关新增 From 头、Contact 参数、呼入 Context、呼入 Extension 和备注配置。
- 动态 Gateway XML 改为从数据库输出上述参数，不再依赖硬编码值。
- 网关编辑页按“注册与保活”“高级配置”分区展示，并在列表中展示注册续期间隔。
- 修改已启用网关后继续通过 `killgw + reloadxml + rescan` 更新 FreeSWITCH 运行态。

当前线路建议配置：

- `ping=0`，避免运营商不响应 OPTIONS 导致网关被误判为 DOWN。
- `expireSeconds=60`、`retrySeconds=30`，使用 REGISTER 保持 NAT 映射。
- 如果仍出现 NAT 呼入空窗，可逐步降低 `expireSeconds`，并观察运营商是否返回 `423 Interval Too Brief`。

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

### 2026-06-11：增强动态 Dialplan 真实呼入参数兼容

本次完成：

- 动态 Dialplan 支持从 `destination_number`、`Caller-Destination-Number`、`Hunt-Destination-Number`、`variable_destination_number`、`sip_to_user`、`variable_sip_to_user`、`sip_req_user`、`variable_sip_req_user` 提取被叫号码。
- 支持清理 `sip:`、`tel:`、`号码@域名` 和 SIP 参数格式。
- 呼入路由先按节点 SIP 域精确匹配，失败后按租户内唯一号码兜底查找，并校验号码绑定节点处于启用状态。

下一步：

- 重新部署 CallNexus 后进行真实外线呼入，查看后端“动态呼入路由按号码兜底匹配成功”或“动态拨号计划匹配到固定分机路由”日志。
- 如果仍返回 `not found`，确认真实请求是否进入 `/api/internal/freeswitch/dialplan`。

### 2026-06-11：完成外线呼入联调

联调结论：

- 网关注册状态正常，但启用 SIP OPTIONS 探测时无法正常呼入。
- 将网关 `ping` 设置为 `0` 后，外线电话可以正常拨入。
- 该运营商线路后续必须保持 `ping=0`，动态 Gateway XML 在 `ping <= 0` 时完全不输出 `ping` 参数，避免 FreeSWITCH 继续执行 SIP OPTIONS 探测。
- 如果运行一段时间后仍出现 `Ping failed`，说明 FreeSWITCH 仍加载着旧运行态或重复网关配置，需要先清理重复网关并重新加载 external profile。

下一步：

- 验证真实呼入后的响铃、接听、双向语音、浏览器来电展示、挂断同步、客户和工单创建完整闭环。

### 2026-06-11：修正 FreeSWITCH 网关运行态同步

本次完成：

- 将新增、修改、删除网关拆分为不同的运行态同步动作。
- 新增网关执行 `reloadxml + external rescan`。
- 修改已启用网关执行 `external killgw + 等待释放 + reloadxml + external rescan`，确保 `ping`、密码、代理地址等修改真正生效。
- 删除或停用网关只执行 `external killgw`，不再执行 `rescan`，避免网关被重新拉取。
- 正确处理网关启用、停用、改名和更换 FreeSWITCH 节点的状态转换。

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
