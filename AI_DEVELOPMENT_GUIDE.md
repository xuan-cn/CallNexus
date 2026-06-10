# CallNexus AI 开发协作规范

本文档给后续 AI 和开发者使用。进入本项目后，先阅读：

1. `AI_DEVELOPMENT_GUIDE.md`：当前项目模块职责、落代码位置、扩展规则。
2. `CALL_CENTER_ENGINEERING_GUIDE.md`：呼叫中心系统总体工程规范。
3. 相关模块已有代码：先模仿现有风格，再做最小必要改动。

## 1. 项目结构

```text
CallNexus/                  后端，Spring Boot + RuoYi-Vue-Plus 基座
CallNexus-UI/               前端，Vue 3 + Element Plus
CALL_CENTER_ENGINEERING_GUIDE.md
AI_DEVELOPMENT_GUIDE.md
```

后端业务代码主要在：

```text
CallNexus/callnexus-modules/
```

前端呼叫中心代码主要在：

```text
CallNexus-UI/src/views/callcenter/
CallNexus-UI/src/api/callcenter/
CallNexus-UI/src/layout/components/AgentToolbar.vue
CallNexus-UI/src/layout/components/DynamicBusinessFormDialog.vue
CallNexus-UI/src/components/CallCenterBusinessDetail/
```

## 2. 后端模块职责

### `callnexus-resource`

负责呼叫中心资源配置，不处理实时通话业务。

当前职责：

- FreeSWITCH 节点管理：`org.dromara.resource.node`
- SIP 账号管理：`org.dromara.resource.sip`
- FreeSWITCH 内部 XML Curl 接口：`org.dromara.resource.freeswitch`
- FreeSWITCH XML 渲染：`org.dromara.resource.freeswitch.xml`

适合放这里的功能：

- SIP 分机、线路、中继、网关、号码资源。
- FreeSWITCH 节点连接配置。
- `mod_xml_curl` 的 directory、dialplan、acl、gateway XML 输出。
- 资源查询服务，供 `callnexus-call`、`callnexus-agent` 调用。

不要放这里：

- 坐席状态机。
- 当前通话状态。
- 客户、工单、跟进记录。
- ESL 连接监听逻辑。

### `callnexus-esl`

负责 FreeSWITCH ESL 的连接、命令执行、事件监听和事件分发适配。

当前职责：

- 命令网关：`FreeSwitchEslCommandGateway`
- 监听管理：`FreeSwitchEslEventListenerManager`
- ESL 事件对象：`FreeSwitchEslEvent`
- 事件分发：`EslEventDispatcher`
- 事件处理接口：`EslEventHandler`
- 通话事件适配：`TelephonyEslEventHandler`

设计规则：

- Listener 只负责连接、认证、订阅、解析事件。
- Dispatcher 负责把事件分给 Handler。
- 每类业务新增独立 Handler，不要继续堆到 Listener 里。
- ESL 模块可以依赖 call 模块定义的事件常量和通话处理接口，但不要写客户、工单等业务逻辑。

新增 ESL 事件处理时：

```text
1. 在 EslEventNames / EslHeaders 添加常量。
2. 如需订阅新事件，加入 EslEventNames.subscribedChannelEvents()。
3. 新建 EslEventHandler 实现类。
4. Handler 内只做事件适配或调用应用服务，不直接写复杂业务。
```

### `callnexus-call`

负责通话控制、当前通话状态、坐席实时状态联动和前端实时推送。

当前职责：

- 呼叫控制 API：拨打、挂断、查询当前通话。
- 通话命令抽象：`TelephonyCommandGateway`
- 通话事件抽象：`TelephonyEvent`
- 通话事件处理：`TelephonyEventHandlerImpl`
- ESL 常量：`EslEventNames`、`EslHeaders`
- Redis 保存短期实时状态：当前通话、UUID 映射、坐席 presence。
- WebSocket 推送坐席实时通话事件。

设计规则：

- MySQL 保存业务事实，Redis 保存短生命周期实时状态。
- 不要把 FreeSWITCH Socket 细节写到 call 模块，使用 `TelephonyCommandGateway` 抽象。
- 不要把客户/工单写入 call 模块。
- 后续话单、录音、通话明细可以放在 call 模块，但要和实时状态分开。

### `callnexus-agent`

负责坐席基础资料和签入签出状态。

当前职责：

- 坐席管理。
- 坐席绑定系统用户和 SIP 账号。
- 当前登录用户获取坐席配置。
- 坐席 presence 状态。

设计规则：

- 坐席是业务人员能力，不等于 SIP 账号。
- SIP 账号属于资源模块，坐席只保存绑定关系。
- 当前通话状态不应长期保存在坐席表，使用 Redis 实时状态。

### `callnexus-customer`

负责客户、工单、动态表单模板和表单提交。

当前职责：

- 客户管理：`org.dromara.customer.customer`
- 工单管理：`org.dromara.customer.ticket`
- 表单模板：`org.dromara.customer.form`
- 客户跟进记录。
- 创建客户/工单时保存动态字段。

设计规则：

- 默认字段和动态字段要分开。
- 动态字段值保存 `option_value`，界面展示时根据模板映射 `option_label`。
- 客户号码已存在时，不重复创建客户，应更新现有客户信息。
- 跟进记录只追加，不覆盖历史。

### 预留模块

```text
callnexus-ivr        IVR 流程、语音菜单、按键路由
callnexus-outbound   外呼名单、任务、策略、预测外呼
callnexus-job        调度任务
```

这些模块已存在基础目录或 Maven 模块时，先确认是否已接入 `pom.xml` 和 `callnexus-admin` 依赖，再开发。

## 3. 分层规范

后端优先使用：

```text
controller  HTTP 适配，只做参数、权限、返回
service     应用服务，用例编排、事务边界
domain      Entity、Request、Response、枚举、领域对象
mapper      MyBatis-Plus Mapper
xml/render  FreeSWITCH XML 或复杂输出渲染
```

禁止：

- Controller 直接操作 Mapper。
- Controller 拼复杂 XML 或写复杂业务。
- 跨模块直接访问其他模块 Mapper。
- `common` 依赖业务模块。
- 为一个小功能创建过度抽象。

允许：

- 简单 CRUD 使用 `Controller -> ApplicationService -> Mapper`。
- 跨模块调用对方公开的 Service 接口。
- 实时状态使用 Redis，业务事实使用 MySQL。

## 4. FreeSWITCH XML Curl 设计

当前已实现动态 directory：

```text
FreeSWITCH mod_xml_curl
  -> POST /api/internal/freeswitch/directory
  -> FreeSwitchDirectoryController
  -> SipAccountQueryService.findDirectoryAccount()
  -> FreeSwitchDirectoryXmlRenderer
  -> 返回 FreeSWITCH XML
```

安全规则：

- 接口路径：`/api/internal/freeswitch/**`
- 不需要后台登录 token。
- 必须校验共享密钥：
  - Header：`X-CallNexus-FreeSWITCH-Token`
  - 或 URL 参数：`token=...`
- 配置：
  - `CALLNEXUS_FREESWITCH_DIRECTORY_SECRET`
  - `CALLNEXUS_FREESWITCH_DIRECTORY_TENANT_ID`

XML 扩展规则：

- 不要在 Controller 中继续拼 XML。
- 通用 XML 工具放在：
  - `org.dromara.resource.freeswitch.xml`
- directory 渲染放在：
  - `org.dromara.resource.freeswitch.xml.directory`
- 后续 dialplan、acl、gateway 建议分别建包：

```text
org.dromara.resource.freeswitch.xml.dialplan
org.dromara.resource.freeswitch.xml.acl
org.dromara.resource.freeswitch.xml.gateway
```

账号不存在、停用、参数错误统一返回 FreeSWITCH `not found` XML，不要返回 JSON。

## 5. ESL 监听设计

当前模式：

```text
FreeSwitchEslEventListenerManager
  -> 连接多个启用 FreeSWITCH 节点
  -> auth
  -> event plain ...
  -> parse headers
  -> FreeSwitchEslEvent
  -> EslEventDispatcher
  -> EslEventHandler
```

通话事件当前通过：

```text
TelephonyEslEventHandler
  -> TelephonyEventHandlerImpl
```

新增事件处理示例：

```java
@Component
public class CdrEslEventHandler implements EslEventHandler {
    @Override
    public boolean supports(FreeSwitchEslEvent event) {
        return EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName());
    }

    @Override
    public void handle(FreeSwitchEslEvent event) {
        // 调用话单应用服务
    }
}
```

注意：

- Handler 抛异常不能断开 ESL 监听，Dispatcher 会捕获并记录。
- Listener 中不要写业务规则。
- 事件名和 Header 必须使用 `EslEventNames`、`EslHeaders`。
- 事件订阅列表集中放在 `EslEventNames.subscribedChannelEvents()`。

## 6. 前端模块职责

### 页面目录

```text
src/views/callcenter/freeswitch-node     FreeSWITCH 节点管理
src/views/callcenter/sip-account         SIP 账号管理
src/views/callcenter/agent               坐席管理
src/views/callcenter/form-template       动态表单模板
src/views/callcenter/customer            客户管理
src/views/callcenter/ticket              工单管理
```

### API 目录

```text
src/api/callcenter/*
```

每个业务目录优先保持：

```text
index.ts   请求方法
types.ts   类型定义，复杂模块必须拆出
```

### 全局坐席工具条

```text
src/layout/components/AgentToolbar.vue
```

职责：

- 坐席签入签出。
- 示忙示闲。
- 发起/挂断呼叫。
- 接收实时通话状态。
- 打开创建客户/工单弹窗。

### 动态客户/工单弹窗

```text
src/layout/components/DynamicBusinessFormDialog.vue
```

职责：

- 创建客户。
- 创建工单。
- 客户号码存在时回填并更新现有客户。
- 默认字段和自定义字段分区展示。
- 自定义字段按 `layoutSpan` 控制半行/整行。

### 客户/工单详情

```text
src/components/CallCenterBusinessDetail/index.vue
```

职责：

- 客户详情。
- 工单详情。
- 自定义字段按模板布局展示。
- 选项字段展示 `label`，保存值仍为 `value`。
- 右侧 Tabs：
  - 跟进记录
  - 通话记录预留

## 7. 动态表单规范

字段类型：

```text
INPUT
TEXTAREA
RADIO
CHECKBOX
SELECT
MULTI_SELECT
NUMBER
DATE
DATETIME
```

布局字段：

```text
layoutSpan = 12  一行两个
layoutSpan = 24  独占整行
```

选项规则：

- `option_value` 是数据库保存值，稳定、适合英文和枚举。
- `option_label` 是界面展示值，例如 `男`、`有意向`。
- 详情页、列表页、导出页都应展示 label，不直接展示 value。

保存规则：

- 动态字段提交前要校验字段编码属于模板。
- 多选值保存数组。
- 客户/工单详情根据模板字段顺序展示，不按 JSON 对象顺序展示。

## 8. 数据库和迁移

所有业务表结构变更使用：

```text
CallNexus/callnexus-admin/src/main/resources/db/migration/V*_*.sql
```

规则：

- 新表必须带 `tenant_id`。
- 业务表使用逻辑删除字段 `deleted`。
- 多租户唯一索引一般包含 `tenant_id` 和 `deleted`。
- Flyway 文件只新增，不修改已执行版本。
- 如果用户说自己执行 SQL，仍要提供迁移文件。

已存在关键迁移：

```text
V4__add_dynamic_form_templates.sql
V5__add_customer_and_ticket.sql
V6__add_customer_ticket_menus.sql
V7__add_form_field_layout.sql
V8__add_customer_follow_up.sql
```

## 9. 安全和配置

敏感配置使用环境变量：

```text
CALLNEXUS_DATA_ENCRYPT_KEY
CALLNEXUS_FREESWITCH_DIRECTORY_SECRET
CALLNEXUS_FREESWITCH_DIRECTORY_TENANT_ID
```

规则：

- SIP 密码使用 `@EncryptField`，不要在管理接口返回明文。
- FreeSWITCH directory 内部 DTO 可以读取明文密码，但只能用于内部 XML 接口。
- 内部接口即使放行后台登录，也必须有自己的共享密钥校验。
- 不要在日志中打印 SIP 密码、ESL 密码、directory token。

## 10. 开发流程建议

后续 AI 开发时按以下顺序：

1. 读本文件和相关模块现有代码。
2. 明确功能属于哪个模块。
3. 优先复用已有 Service、DTO、Mapper、API 风格。
4. 小步修改，不做无关重构。
5. 后端改表时新增 Flyway SQL。
6. 前端新增接口时同步补类型。
7. 后端新增或修改关键运行节点时，必须补充中文日志，便于本地联调、服务器排查和后续 AI 接手。
8. 修改 UI 后运行前端检查。
9. 用户明确不需要单元测试时，不主动补测试。
10. 用户说自己编译后端时，不主动跑 Maven 编译。

关键运行节点日志要求：

- 关键入口、外部系统请求、FreeSWITCH XML Curl、ESL 连接/断开/重连、呼叫控制命令、路由决策、状态变更、异步任务开始/结束/失败，都应有中文日志。
- 日志应包含必要上下文，例如 `tenantId`、`nodeId`、`callId`、`channelId`、`agentId`、`section`、`purpose`、`domain`、执行结果和耗时。
- 日志内容要面向排查问题，避免只写“开始执行”“执行完成”这类无上下文日志。
- 禁止在日志中打印密码、token、SIP 密码、ESL 密码、完整客户隐私、完整通话内容、完整 FreeSWITCH XML。
- 需要观察敏感响应时，只记录长度、数量、状态或脱敏摘要。

推荐检查命令：

```bash
# 前端按文件检查
npm run lint:eslint -- <changed files>
npx vue-tsc --noEmit --skipLibCheck

# 后端轻量检查
git diff --check
```

## 11. 常见功能落点

新增 SIP 账号字段：

```text
后端：callnexus-resource / org.dromara.resource.sip
前端：src/views/callcenter/sip-account + src/api/callcenter/sip-account
```

新增 FreeSWITCH XML Curl section：

```text
后端：callnexus-resource / org.dromara.resource.freeswitch
XML：org.dromara.resource.freeswitch.xml.<section>
```

新增 ESL 事件业务：

```text
事件常量：callnexus-call / EslEventNames, EslHeaders
事件监听：callnexus-esl
业务处理：新增 EslEventHandler 或调用对应应用服务
```

新增坐席能力：

```text
后端：callnexus-agent
实时通话联动：callnexus-call
前端：AgentToolbar.vue 或 views/callcenter/agent
```

新增客户字段或详情：

```text
后端：callnexus-customer / customer 或 form
前端：DynamicBusinessFormDialog.vue / CallCenterBusinessDetail
```

新增工单流程：

```text
后端：callnexus-customer / ticket
前端：views/callcenter/ticket
详情组件：CallCenterBusinessDetail
```

新增 IVR：

```text
后端：callnexus-ivr
FreeSWITCH dialplan XML：callnexus-resource.freeswitch.xml.dialplan
ESL 事件监听：callnexus-esl handler
```

新增外呼：

```text
后端：callnexus-outbound
拨号控制：调用 callnexus-call 的公开服务
客户名单：通过 callnexus-customer 公开服务
```

外呼模式必须区分：

```text
PREVIEW       预览外呼，真人坐席先看资料再手动拨打。
PROGRESSIVE   自动/渐进式外呼，系统自动拨客户，接通后分配真人坐席。
PREDICTIVE    预测外呼，系统按算法提前多拨，接通后分配真人坐席，需要控制弃呼率。
ROBOT         机器人外呼，客户接通后进入机器人话术，必要时转人工。
```

注意：自动外呼不是机器人外呼。自动外呼描述的是拨号方式，坐席可以是真人，也可以是机器人。第一阶段优先做真人外呼：`PREVIEW -> PROGRESSIVE`，机器人外呼放到 ASR/TTS/NLU/话术/转人工能力成熟后再做。

## 12. OpenCallHub 页面迁移映射

OpenCallHub 前端参考目录：

```text
E:\coding_idea\call_freeswitch\OpenCallHub_Front\src\views
```

CallNexus 当前没有完整迁移这些页面。迁移时不要直接复制后端接口调用，必须按 CallNexus 模块边界重新设计 API、DTO、菜单和权限。

### 12.1 总体迁移规则

迁移旧页面时按以下流程：

1. 先确认页面属于资源配置、通话业务、客户业务、IVR 还是外呼。
2. 后端先补领域模型、接口和 Flyway 菜单/表结构。
3. 前端迁移到 `CallNexus-UI/src/views/callcenter` 下的新目录。
4. API 放到 `CallNexus-UI/src/api/callcenter/<feature>`。
5. 旧页面只参考交互和字段，不直接沿用旧接口路径。
6. 菜单 SQL 放到后端 Flyway 文件中，保持租户和权限体系一致。
7. 如果页面依赖 FreeSWITCH XML，XML 输出统一放到 `callnexus-resource.freeswitch.xml` 下。
8. 如果页面依赖实时事件，ESL 事件统一通过 `callnexus-esl` 的 Handler 扩展。

### 12.2 `CallingConfigure`

旧目录：

```text
OpenCallHub_Front/src/views/CallingConfigure
```

迁移建议：

| OpenCallHub 页面 | CallNexus 后端模块 | CallNexus 前端目录 | 菜单建议 | 说明 |
| --- | --- | --- | --- | --- |
| `AgentManagement` | `callnexus-agent` | `src/views/callcenter/agent` | 坐席管理 | 当前已有基础坐席管理，不要重复建页面，优先合并字段。 |
| `SIPPhoneNumber` | `callnexus-resource / sip` | `src/views/callcenter/sip-account` | SIP账号管理 | 当前已有基础 SIP 账号管理，后续补自动 directory 能力展示。 |
| `PhoneNumber` | `callnexus-resource` | `src/views/callcenter/phone-number` | 号码管理 | 管理 DID、外显号码、号码归属。 |
| `PhoneNumberRoute` | `callnexus-resource` + `callnexus-ivr` | `src/views/callcenter/number-route` | 号码路由 | 入线号码路由到坐席、队列、IVR、外呼任务。 |
| `PhonePool` | `callnexus-resource` 或 `callnexus-outbound` | `src/views/callcenter/phone-pool` | 号码池 | 如果用于外呼外显，归 outbound；如果是纯号码资源，归 resource。 |
| `SkillManagement` | `callnexus-agent` | `src/views/callcenter/skill` | 技能组管理 | 技能组、坐席技能、队列分配的基础配置。 |
| `ScheduleManagement` | `callnexus-resource` 或 `callnexus-agent` | `src/views/callcenter/schedule` | 日程管理 | 工作时间、节假日、路由时间条件。 |
| `VoiceFileManagement` | `callnexus-resource` | `src/views/callcenter/voice-file` | 语音文件 | 上传、试听、绑定 IVR/放音节点。文件能力复用基座 OSS。 |
| `CallRecordManagement` | `callnexus-call` | `src/views/callcenter/call-record` | 通话记录 | 话单、录音、通话明细。需要先做通话记录落库。 |
| `AIEngineManagement` | `callnexus-outbound` 或机器人模块 | `src/views/callcenter/ai-engine` | AI引擎 | 暂缓，等机器人/智能外呼边界明确后再迁移。 |

注意：

- `AgentManagement`、`SIPPhoneNumber` 当前已有新实现，后续只增量补字段。
- `CallRecordManagement` 不应只做前端页面，必须先在 `callnexus-call` 落库话单。
- `PhoneNumberRoute` 会影响入站路由，后端必须和 dialplan XML/IVR 联动设计。

### 12.3 `FsConfigure`

旧目录：

```text
OpenCallHub_Front/src/views/FsConfigure
```

迁移建议：

| OpenCallHub 页面 | CallNexus 后端模块 | CallNexus 前端目录 | 菜单建议 | 说明 |
| --- | --- | --- | --- | --- |
| `Configure` | `callnexus-resource / node` | `src/views/callcenter/freeswitch-node` | FreeSWITCH节点 | 当前已有基础节点管理，继续扩展。 |
| `GatewayManagement` | `callnexus-resource` | `src/views/callcenter/freeswitch-gateway` | 网关管理 | 后续输出 gateway XML 或通过 FS 配置同步。 |
| `DialPlanManagement` | `callnexus-resource.freeswitch.xml.dialplan` + `callnexus-ivr` | `src/views/callcenter/dialplan` | 拨号计划 | 不建议直接照搬旧页面，要以号码路由、IVR、队列为业务入口生成 dialplan。 |
| `AccessControl` | `callnexus-resource.freeswitch.xml.acl` | `src/views/callcenter/access-control` | ACL管理 | 用于 FreeSWITCH ACL XML，需谨慎控制误配置。 |
| `ModuleConfigure` | `callnexus-resource` | `src/views/callcenter/freeswitch-module` | 模块配置 | 暂缓。模块启停更偏运维能力，需限制权限。 |

FreeSWITCH 配置原则：

- 节点、SIP账号、网关、ACL 属于资源配置。
- Dialplan 不应变成随意编辑 XML 的页面，应从业务路由生成。
- 动态 XML 输出统一走 `mod_xml_curl`，不要在前端直接编辑 FreeSWITCH 服务器文件。
- 需要服务器操作时，优先通过后台受控命令或让服务器端 Claude Code 配置。

### 12.4 `IVRManagement`

旧目录：

```text
OpenCallHub_Front/src/views/IVRManagement
```

迁移建议：

```text
后端模块：callnexus-ivr
前端目录：src/views/callcenter/ivr
菜单：IVR管理
XML输出：callnexus-resource.freeswitch.xml.dialplan
事件监听：callnexus-esl handler
```

IVR 模块建议拆分：

```text
ivr-flow          IVR 流程定义
ivr-node          播放、按键、转接、条件、挂机等节点
ivr-version       发布版本
ivr-runtime       当前执行状态，可用 Redis
ivr-statistics    节点命中、按键统计
```

设计规则：

- IVR 设计器保存业务流程，不直接保存 FreeSWITCH 原始 XML。
- 发布后生成 dialplan XML 或 XML Curl 响应。
- 按键事件通过 ESL 监听，进入 IVR runtime。
- IVR 节点引用语音文件时，使用 `VoiceFileManagement` 对应资源。

### 12.5 `callTaskConfigure`

旧目录：

```text
OpenCallHub_Front/src/views/callTaskConfigure/CallTask
```

迁移建议：

```text
后端模块：callnexus-outbound
前端目录：src/views/callcenter/outbound-task
菜单：外呼任务
```

外呼任务建议包含：

```text
任务基础信息：名称、类型、状态、时间窗口
名单来源：客户、号码包、导入文件
拨号策略：预览外呼、自动/渐进式外呼、预测外呼、机器人外呼
重呼策略：失败原因、间隔、最大次数
坐席分配：技能组、坐席池
结果记录：接通、未接、拒接、失败原因
```

外呼策略定义：

| 策略 | 坐席类型 | 说明 | 第一阶段建议 |
| --- | --- | --- | --- |
| `PREVIEW` 预览外呼 | 真人 | 坐席先看到客户资料，再点击拨打。适合高价值客户和复杂业务。 | 优先做 |
| `PROGRESSIVE` 自动/渐进式外呼 | 真人 | 系统自动拨号，客户接通后分配给空闲坐席。适合回访、通知确认、线索跟进。 | 第二步做 |
| `PREDICTIVE` 预测外呼 | 真人 | 根据接通率和坐席空闲率提前多拨，提升坐席利用率。需要弃呼率控制。 | 暂缓 |
| `ROBOT` 机器人外呼 | 机器人，可转人工 | 接通后进入机器人话术，依赖 ASR/TTS/NLU/LLM 和话术流程。 | 暂缓 |

开发边界：

- `callnexus-outbound` 负责任务、名单、策略、重呼、结果。
- `callnexus-call` 只负责实际拨号、挂断、通话控制和话单，不保存外呼任务规则。
- `callnexus-customer` 提供客户资料、号码、标签、人群，不保存外呼执行状态。
- 机器人外呼可以先作为 outbound 的一种策略枚举预留，但不要在第一阶段实现机器人话术和 AI 引擎。
- 自动外呼接通后分配真人坐席时，需要依赖 `callnexus-agent` 的坐席在线、空闲、技能组和队列能力。

设计规则：

- 拨号动作调用 `callnexus-call` 的公开服务。
- 名单和客户资料通过 `callnexus-customer` 公开服务查询。
- 任务调度可以接入 `callnexus-job` 或 SnailJob。
- 不要把外呼任务逻辑放到 `callnexus-call`，call 模块只负责通话控制和话单。

### 12.6 `Customer`

旧目录：

```text
OpenCallHub_Front/src/views/Customer
```

迁移建议：

| OpenCallHub 页面 | CallNexus 后端模块 | CallNexus 前端目录 | 菜单建议 | 说明 |
| --- | --- | --- | --- | --- |
| `template` | `callnexus-customer / form` | `src/views/callcenter/form-template` | 表单模板 | 当前已有动态模板管理，优先合并能力。 |
| `field` | `callnexus-customer / form` | `src/views/callcenter/form-template` 或 `customer-field` | 字段管理 | 当前字段随模板维护，不建议单独做全局字段，除非后续有字段库。 |
| `pool` | `callnexus-customer` | `src/views/callcenter/customer-pool` | 客户公海 | 后续做客户分配、领取、回收。 |
| `crowd` | `callnexus-customer` 或 `callnexus-outbound` | `src/views/callcenter/customer-crowd` | 客户人群 | 如果用于外呼筛选，可由 outbound 引用。 |

当前已有：

```text
src/views/callcenter/customer
src/views/callcenter/ticket
src/views/callcenter/form-template
```

继续迁移时优先补：

1. 客户公海。
2. 客户标签/人群。
3. 客户导入导出。
4. 与外呼任务名单联动。

### 12.7 `RobotConfigure`

旧目录：

```text
OpenCallHub_Front/src/views/RobotConfigure
```

迁移建议：

| OpenCallHub 页面 | CallNexus 后端模块 | CallNexus 前端目录 | 说明 |
| --- | --- | --- | --- |
| `robot` | 未来机器人模块或 `callnexus-outbound` | `src/views/callcenter/robot` | 智能外呼机器人。 |
| `engine` | 未来机器人模块 | `src/views/callcenter/robot-engine` | ASR/TTS/NLU/LLM 引擎配置。 |
| `intent` | 未来机器人模块 | `src/views/callcenter/robot-intent` | 意图管理。 |
| `knowledge` | 未来知识库模块 | `src/views/callcenter/knowledge` | 知识库。 |

暂缓迁移。原因：

- 机器人模块边界尚未稳定。
- 需要先完成基础通话、外呼任务、IVR、话单。
- 涉及 AI 服务商配置、费用、安全和异步任务。

### 12.8 AI 坐席辅助与知识库

AI 坐席辅助不是机器人外呼。两者边界必须分清：

```text
机器人外呼：AI 替人和客户说话，客户主要面对机器人。
AI 坐席辅助：人工坐席和客户通话，AI 在旁边实时转写、检索知识库、推荐话术、生成摘要和草稿。
```

AI 坐席辅助目标：

```text
人工坐席接听电话
  -> 系统实时转写双方语音
  -> 根据客户表达识别意图、情绪、关键词
  -> 检索对应知识库
  -> 推荐可回复给客户的话术或句子
  -> 前端实时展示双方对话流和 AI 建议
  -> 通话结束生成摘要、跟进记录、客户资料草稿、工单草稿
```

建议后端模块：

```text
callnexus-ai-assistant
```

如果暂时不新建模块，可先在未来机器人模块稳定前作为独立预留，不要混入 `callnexus-outbound`。`callnexus-outbound` 只处理外呼任务和拨号策略，不应承载人工通话中的 AI 辅助逻辑。

建议包职责：

```text
conversation      通话实时对话流、说话人、时间戳、callId 绑定
transcription     ASR 转写任务和结果
knowledge         知识库、文档、分段、向量索引、检索结果
suggestion        推荐回复、命中知识、置信度
extraction        客户资料、工单字段、标签、意向等结构化抽取
summary           通话摘要、处理结果、待跟进事项
provider          ASR/LLM/Embedding/TTS 服务商适配
```

和现有模块关系：

| 模块 | 关系 |
| --- | --- |
| `callnexus-call` | 提供 `callId`、通话开始/结束事件、通话双方号码、录音和话单。 |
| `callnexus-esl` | 监听 `CHANNEL_ANSWER`、`CHANNEL_HANGUP_COMPLETE` 等事件，触发转写开始/结束。 |
| `callnexus-agent` | 提供当前坐席、坐席用户、技能组、在线状态。 |
| `callnexus-customer` | 接收 AI 抽取后的客户资料草稿、工单草稿、跟进记录。 |
| `callnexus-resource` | 提供语音文件、节点、线路等资源，不直接处理 AI。 |

前端落点：

```text
AgentToolbar / 坐席工作台
  实时对话流
  AI 推荐回复
  知识库命中
  一键填充客户资料
  一键创建工单
  通话结束摘要
  自动生成跟进记录草稿
```

也可以新增：

```text
src/views/callcenter/knowledge          知识库管理
src/views/callcenter/ai-assistant       AI 坐席辅助配置
src/api/callcenter/ai-assistant
src/api/callcenter/knowledge
```

推荐实现阶段：

1. 对话流 UI 骨架。
   - 先不接真实 ASR。
   - 后端可以模拟推送转写消息。
   - 所有消息必须绑定 `callId`。

2. 实时语音转写。
   - 接 ASR。
   - 区分客户和坐席两路说话人。
   - 前端展示：

   ```text
   客户：我想查一下订单
   坐席：请问您的手机号是多少
   ```

3. 知识库检索推荐。
   - 根据客户最近一句话或最近几轮对话检索知识库。
   - 返回推荐回复和来源文档。
   - 前端必须展示知识来源，不能只展示 AI 答案。

4. AI 结构化抽取。
   - 抽取客户姓名、电话、地址、性别、意向、问题类型、紧急程度等字段。
   - 结果先进入草稿，不自动提交。

5. 一键创建客户或工单。
   - AI 填好表单草稿。
   - 坐席确认后提交到 `callnexus-customer`。
   - 第一阶段不要完全自动创建，避免误建脏数据。

6. 通话结束摘要和跟进记录。
   - 自动生成通话摘要、客户诉求、处理结果、待跟进事项。
   - 生成客户跟进记录草稿。
   - 坐席确认后保存。

7. 半自动化能力。
   - 对低风险场景可以配置自动生成工单草稿。
   - 自动提交必须有租户级开关、业务规则和审计日志。

实时数据设计：

```text
conversation_session
  callId
  agentId
  customerNumber
  status
  startedAt
  endedAt

conversation_message
  callId
  speaker: CUSTOMER / AGENT / AI
  text
  occurredAt
  confidence

ai_suggestion
  callId
  sourceMessageId
  suggestionText
  knowledgeSource
  confidence

ai_extraction
  callId
  businessType: CUSTOMER / TICKET / FOLLOW_UP
  draftData
  status: DRAFT / CONFIRMED / REJECTED
```

实现规则：

- AI 调用必须异步，不能阻塞 ESL 监听线程。
- 对话流、建议、摘要都必须绑定 `callId`。
- AI 结果默认是建议或草稿，人工确认后才写入客户/工单。
- 知识库答案必须可追溯来源文档。
- 不要在日志中打印完整客户隐私和敏感对话。
- 要支持租户隔离，不同租户知识库不能串用。
- ASR、LLM、Embedding 服务商用 provider 适配层，不要把某个厂商 SDK 写死在业务服务中。
- 涉及录音和转写时，要考虑客户授权、合规提示、数据留存周期。

AI 坐席辅助和机器人外呼的复用边界：

```text
可复用：
  知识库
  意图识别
  ASR/LLM provider
  摘要和结构化抽取

不可混用：
  通话控制流程
  外呼任务策略
  机器人话术状态机
  人工坐席实时辅助 UI
```

开发优先级：

```text
1. 人工接听时的实时对话流
2. 知识库推荐回复
3. 通话摘要和跟进记录草稿
4. 客户/工单字段抽取草稿
5. 坐席确认后一键创建客户/工单
6. 机器人外呼
```

### 12.9 `OverView`

旧目录：

```text
OpenCallHub_Front/src/views/OverView
```

迁移建议：

```text
前端：首页或 src/views/callcenter/dashboard
后端：按数据来源分别在 call、agent、customer、outbound 提供统计接口
```

当前 CallNexus 已经有首页运营概览。后续优化：

- 今日呼叫量、接通率、平均等待。
- 坐席在线、空闲、忙碌、离线。
- 服务健康：FreeSWITCH、ESL、WebSocket。
- 任务待办：未接回拨、异常呼叫、坐席状态异常。

### 12.10 `System`

旧目录：

```text
OpenCallHub_Front/src/views/System
```

不要迁移到 CallNexus 呼叫中心模块。CallNexus 已经基于 RuoYi-Vue-Plus 提供：

```text
用户管理
角色管理
菜单管理
日志管理
租户管理
权限控制
```

如需扩展系统能力，优先使用现有 `callnexus-system` 模块和已有前端 `src/views/system`。

### 12.11 推荐迁移顺序

按依赖关系和业务价值，推荐顺序：

```text
1. GatewayManagement      FreeSWITCH 网关管理
2. PhoneNumber            号码管理
3. PhoneNumberRoute       号码路由
4. DialPlanManagement     动态拨号计划输出
5. IVRManagement          IVR 流程
6. CallRecordManagement   通话记录/话单
7. CallTask               外呼任务
8. SkillManagement        技能组/队列增强
9. Customer pool/crowd    客户公海/人群
10. RobotConfigure        机器人相关
```

不要先迁移机器人或复杂外呼。没有网关、路由、话单和 IVR 基础时，机器人页面只能变成空壳。

### 12.12 菜单规划建议

一级菜单仍使用当前呼叫中心菜单，二级建议：

```text
呼叫中心
  运营概览
  坐席工作台
  资源配置
    FreeSWITCH节点
    SIP账号
    网关管理
    号码管理
    ACL管理
    语音文件
  路由与IVR
    号码路由
    IVR管理
    拨号计划
  坐席与队列
    坐席管理
    技能组
    队列管理
    日程管理
  客户与工单
    客户管理
    工单管理
    表单模板
    客户公海
    客户人群
  外呼
    外呼任务
    外呼名单
    重呼策略
  报表与记录
    通话记录
    录音记录
    坐席报表
  AI辅助
    知识库
    AI坐席辅助配置
    对话质检
```

菜单 SQL 要随对应功能的后端迁移文件一起提交，不要只在前端写静态路由。

## 13. 当前技术债和后续优化

优先级较高：

- 话单和通话记录落库。
- 工单跟进记录和客户跟进记录统一抽象。
- FreeSWITCH dialplan 动态 XML。
- FreeSWITCH gateway 动态配置。
- ESL 事件增加持久化事件日志，便于排查。
- XML Curl 接口增加 IP 白名单。

暂不建议：

- 过早引入 Kamailio。
- 过早拆成大量微服务。
- 在 common 模块放业务工具。
- 为所有 CRUD 过度设计复杂领域模型。
