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
7. 修改 UI 后运行前端检查。
8. 用户明确不需要单元测试时，不主动补测试。
9. 用户说自己编译后端时，不主动跑 Maven 编译。

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

## 12. 当前技术债和后续优化

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
