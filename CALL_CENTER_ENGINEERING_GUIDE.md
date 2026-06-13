# 呼叫中心系统工程与编码规范

## 1. 文档目标

本文档用于指导新一代呼叫中心系统从零开发。

设计参考 OpenCallHub 中合理的模块边界、事件驱动、策略模式和 FreeSWITCH ESL 处理方式，同时修正以下问题：

- 模块依赖混乱和 Service 职责过重
- 大量魔法数字、弱类型参数和重复代码
- 同步事务与异步事件边界不清晰
- 呼叫实时状态与业务数据边界不清晰
- 缺少测试、日志规范和质量门禁
- 前后端接口命名、错误码和分页格式不统一

本文档中的“必须”是代码评审和合并要求。

---

## 2. 技术栈建议

### 2.1 后端

- Java 17 LTS
- Spring Boot 3
- Sa-Token
- MyBatis-Plus，用于简单 CRUD
- MyBatis XML，用于复杂查询和报表
- MySQL 8
- Redis
- FreeSWITCH ESL
- SnailJob，用于需要持久化、可恢复和可观测的任务调度
- Spring Application Event，仅用于单实例内部解耦
- RabbitMQ 或 Kafka，用于需要可靠投递的跨模块异步任务
- Flyway，用于数据库版本管理
- Testcontainers，用于集成测试

### 2.2 前端

- Vue 3
- TypeScript 严格模式
- Vite
- Pinia
- Vue Router
- Axios
- Element Plus
- SIP.js 或 JsSIP
- Vitest

### 2.3 基础设施

- FreeSWITCH：媒体、SIP、拨号计划和通话控制
- Kamailio：注册代理、负载均衡和多 FreeSWITCH 路由，第一阶段可不引入
- Nginx：统一入口和 WebSocket 代理
- Prometheus + Grafana：监控
- Loki 或 ELK：日志检索

---

## 3. 总体架构原则

### 3.1 当前项目基座

当前项目名称为 `CallNexus`，管理后台基于 RuoYi-Vue-Plus 5.6.1：

```text
CallNexus/
  CallNexus/   后端
  CallNexus-UI/          前端
```

必须保留并复用基座已有能力：

```text

```

不要重复创建认证、系统管理、工作流、多租户、WebSocket 和文件模块。

### 3.2 呼叫中心业务模块

第一阶段优先采用三个业务 Maven 模块，统一放入：

```text
CallNexus/callnexus-modules/
```

模块名称：

```text
callnexus-callcenter   呼叫资源、坐席、技能组、队列、呼叫控制、ESL、IVR、话单
callnexus-customer     客户、联系人、客户公海、标签和人群
callnexus-outbound     外呼名单、外呼任务、重呼策略、预测外呼和机器人外呼
```

推荐 Java 包名：

```text
org.dromara.callcenter
org.dromara.customer
org.dromara.outbound
```

`callnexus-callcenter` 内部必须继续按领域包隔离：

```text
org.dromara.callcenter.resource
org.dromara.callcenter.agent
org.dromara.callcenter.call
org.dromara.callcenter.esl
org.dromara.callcenter.ivr
```

当某个领域规模、团队所有权或部署要求明确后，再拆分为独立 Maven 模块。禁止开发初期为了形式完整创建大量互相依赖的小模块。

新增业务模块时必须同时完成：

1. 在 `ruoyi-modules/pom.xml` 的 `<modules>` 中注册模块。
2. 在根 `pom.xml` 的 `<dependencyManagement>` 中统一管理模块版本。
3. 在 `ruoyi-admin/pom.xml` 中添加需要启动装配的业务模块依赖。
4. Java 包保持在 `org.dromara` 根包下，确保能够被启动类扫描。
5. 数据库脚本、菜单权限和租户隔离配置随模块一起提交。

### 3.3 模块依赖规则

必须遵守：

```text
callnexus-admin → callnexus-modules 中的业务模块
callnexus-outbound → callnexus-customer、callnexus-callcenter
callnexus-customer → RuoYi公共能力
callnexus-callcenter → RuoYi公共能力
业务模块 → 按需依赖 ruoyi-common 子模块
业务模块之间通过公开 Service 接口、领域事件或可靠消息协作
ruoyi-common 不得依赖 CallNexus 业务模块
```

禁止：

- 循环依赖
- 为复用一个类而让整个模块互相依赖
- 将业务逻辑放入 `common`
- Controller 直接调用 Mapper
- 一个模块直接访问另一个模块的 Mapper

### 3.4 数据与实时状态边界

MySQL 保存需要审计、查询和恢复的业务事实：

- 用户、坐席、线路、路由等配置
- 外呼任务和名单
- 通话记录和通话明细
- 操作日志

Redis 保存短生命周期实时状态：

- 坐席在线状态
- 当前通话状态
- FreeSWITCH UUID 与业务 callId 映射
- IVR实例运行状态
- 分布式锁和短期幂等键

Redis 不能作为最终业务事实的唯一存储。

---

## 4. 后端分层规范

每个业务模块内部建议采用：

```text
controller        HTTP接口适配
application       用例编排、事务边界
domain            领域模型、规则、策略、事件
infrastructure    Mapper、Redis、ESL和外部系统实现
```

对于简单 CRUD，可以简化为：

```text
Controller → ApplicationService → Repository
```

### 4.1 Controller

Controller 只负责：

- 参数接收和校验
- 权限声明
- 调用应用服务
- 返回统一响应

禁止：

- 编写业务规则
- 直接操作数据库
- 直接调用 FreeSWITCH
- 捕获所有异常并返回成功

示例：

```java
@RestController
@RequestMapping("/api/v1/outbound-tasks")
@RequiredArgsConstructor
class OutboundTaskController {

    private final OutboundTaskApplicationService applicationService;

    @PostMapping
    @PreAuthorize("@permission.has('outbound:task:create')")
    ApiResponse<Long> create(@Valid @RequestBody CreateOutboundTaskRequest request) {
        return ApiResponse.success(applicationService.create(request));
    }
}
```

### 4.2 Application Service

Application Service 负责：

- 一个业务用例的完整编排
- 事务边界
- 调用领域服务、Repository 和外部端口
- 发布领域事件

必须使用构造器注入。

一个公开方法应对应一个明确用例，例如：

```text
createTask
startTask
pauseTask
importContacts
originateCall
```

禁止使用含义模糊的方法名：

```text
handle
process
execute
doSomething
```

策略接口、事件处理器等明确场景可以使用 `handle` 或 `execute`。

### 4.3 Domain

领域层保存业务规则，不依赖 Controller、数据库实现和 FreeSWITCH SDK。

核心状态必须用枚举或值对象表达：

```java
public enum OutboundTaskStatus {
    DRAFT,
    RUNNING,
    PAUSED,
    FINISHED,
    CANCELLED
}
```

禁止：

```java
if (task.getStatus() == 2) {
}
```

推荐：

```java
task.pause();
```

由领域对象内部校验是否允许暂停。

### 4.4 Repository

Repository 是领域层访问持久化数据的接口。

简单 CRUD 可使用 MyBatis-Plus，复杂查询使用 XML。复杂报表查询可以建立独立 QueryRepository。

禁止：

- 在 Service 拼接 SQL
- 使用字符串拼接构造动态查询
- 跨模块直接使用其他模块 Mapper

---

## 5. 数据对象规范

按职责区分对象：

```text
Request/Command   接口输入或应用命令
Query             查询条件
Response/VO       接口输出
Entity            数据库映射对象
Domain Model      领域行为和规则
Event             已发生的业务事实
```

禁止使用同一个对象贯穿 Controller、Service 和数据库。

禁止在 Request 类中使用 MyBatis 注解。

示例命名：

```text
CreateSipGatewayRequest
UpdateSipGatewayRequest
SipGatewayPageQuery
SipGatewayResponse
SipGatewayEntity
SipGatewayCreatedEvent
```

对象转换优先使用 MapStruct。禁止在复杂业务中大量散落 `BeanUtils.copyProperties`。

---

## 6. API设计规范

### 6.1 URL

使用资源化 URL：

```text
POST   /api/v1/outbound-tasks
PUT    /api/v1/outbound-tasks/{id}
GET    /api/v1/outbound-tasks/{id}
GET    /api/v1/outbound-tasks
DELETE /api/v1/outbound-tasks/{id}
POST   /api/v1/outbound-tasks/{id}/start
POST   /api/v1/outbound-tasks/{id}/pause
```

禁止使用：

```text
/add
/edit/{id}
/page/list
/get/{id}
```

### 6.2 统一响应

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {},
  "traceId": "..."
}
```

分页结构：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 100
}
```

### 6.3 错误码

错误码必须稳定、可检索：

```text
AUTH_TOKEN_EXPIRED
SIP_GATEWAY_NOT_FOUND
CALL_ROUTE_NOT_FOUND
OUTBOUND_TASK_INVALID_STATUS
AGENT_NOT_AVAILABLE
```

禁止仅返回 `"无效ID"` 或 `"操作失败"`。

---

## 7. 呼叫领域设计

### 7.1 统一呼叫标识

必须区分：

- `callId`：系统业务呼叫ID，一次完整呼叫唯一
- `channelId`：FreeSWITCH Channel UUID
- `legId`：呼叫腿标识
- `taskId`：外呼任务ID
- `contactId`：任务联系人ID

任何日志必须至少包含 `traceId`、`callId` 和 `channelId` 中适用的字段。

### 7.2 呼叫状态机

呼叫状态变化必须集中管理：

```text
CREATED
ROUTING
RINGING
ANSWERED
BRIDGED
HANGUP
COMPLETED
FAILED
```

禁止由多个 ESL 事件处理器随意覆盖状态。

### 7.3 ESL事件处理

ESL事件处理器采用“适配器 + 分发器 + 领域服务”：

```text
FreeSWITCH事件
→ EslEventAdapter
→ EslEventDispatcher
→ 对应事件处理器
→ CallStateService
→ 持久化和WebSocket通知
```

事件处理器必须：

- 幂等
- 快速返回
- 不执行长时间阻塞任务
- 对重复、乱序事件有容错
- 保留原始事件的关键字段用于排查

推荐注册方式：

```java
@Component
@HandlesEslEvent(EslEventType.CHANNEL_ANSWER)
class ChannelAnswerHandler implements EslEventHandler {
}
```

分发器启动时构建索引，禁止每次事件遍历全部 Bean 和反射查找注解。

### 7.4 路由策略

不同路由类型使用策略模式：

```java
interface CallRouteStrategy {
    RouteType supports();
    RouteResult route(RouteContext context);
}
```

典型策略：

- AgentRouteStrategy
- SkillGroupRouteStrategy
- IvrRouteStrategy
- ExternalGatewayRouteStrategy
- VoiceFileRouteStrategy

### 7.5 FreeSWITCH访问边界

业务模块不能直接依赖 FreeSWITCH SDK。

定义端口：

```java
interface TelephonyCommandGateway {
    OriginateResult originate(OriginateCommand command);
    void bridge(BridgeCommand command);
    void hangup(HangupCommand command);
    void play(PlayCommand command);
    void record(RecordCommand command);
}
```

`callnexus-callcenter` 中的 `esl.infrastructure` 包提供实现。

---

## 8. 外呼任务设计

### 8.1 任务状态

```text
DRAFT → READY → RUNNING → PAUSED → FINISHED
                          └──────→ CANCELLED
```

状态转换必须由领域对象控制。

### 8.2 外呼策略

不同任务类型使用策略：

```java
interface OutboundStrategy {
    OutboundTaskType supports();
    DialBatchPlan plan(OutboundTaskSnapshot task);
}
```

实现示例：

- PreviewOutboundStrategy
- ProgressiveOutboundStrategy
- PredictiveOutboundStrategy
- RobotOutboundStrategy

任务调度和拨号策略必须分离：

```text
Scheduler：决定什么时候执行
Strategy：决定本轮拨打谁、拨打多少
Dialer：执行拨号命令
```

### 8.3 并发与幂等

必须防止：

- 同一任务被多个节点同时调度
- 同一联系人被重复拨打
- 重复 ESL 事件导致重复更新
- 重试导致重复创建话单

推荐使用：

- 数据库唯一索引
- 乐观锁
- Redis短期锁
- 幂等键

---

## 9. 事件驱动规范

事件必须表示已经发生的事实：

```text
CustomerCreatedEvent
OutboundTaskStartedEvent
CallAnsweredEvent
CallCompletedEvent
```

禁止使用模糊事件：

```text
CustomerEvent
ProcessEvent
HandleEvent
```

事务内发布且要求一致性的事件，使用事务事件监听器：

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
```

重要业务事件必须使用可靠消息或 Outbox Pattern，不能仅依赖 Spring 内存事件。

监听器必须定义失败策略：

- 重试
- 死信
- 告警
- 人工补偿

禁止捕获异常后仅打印日志并当作成功。

---

## 10. 前端编码规范

### 10.1 目录结构

按业务模块组织，而不是仅按技术类型组织：

```text
src/
  modules/
    outbound-task/
      api/
      components/
      pages/
      types/
      stores/
    call-resource/
    agent/
    customer/
  shared/
    api/
    components/
    hooks/
    types/
    utils/
```

### 10.2 TypeScript

必须开启严格模式。

禁止：

- API参数和响应使用 `any`
- 页面中散落接口字段字符串
- 使用未定义结构的对象承载状态

示例：

```ts
export interface OutboundTask {
  id: string
  name: string
  status: OutboundTaskStatus
}
```

### 10.3 API层

API层只负责请求定义，不处理页面状态：

```ts
export function createOutboundTask(
  request: CreateOutboundTaskRequest
): Promise<ApiResponse<string>> {
  return http.post('/api/v1/outbound-tasks', request)
}
```

### 10.4 页面职责

页面组件负责用例编排，复杂表单、表格和实时状态拆为组件或 Hook。

禁止单个 Vue 文件同时承担：

- 查询
- 复杂表单
- WebSocket
- SIP注册
- 呼叫控制
- 大量业务规则

### 10.5 实时连接

WebSocket 和 SIP 连接必须由独立 Store 或 Service 管理：

- 明确连接状态
- 指数退避重连
- 防止重复连接
- 页面销毁时正确释放订阅
- 后端不可用时不阻塞后台配置页面

---

## 11. 日志与可观测性

### 11.1 结构化日志

日志应包含：

```text
traceId
callId
channelId
taskId
agentId
eventType
durationMs
result
```

推荐：

```java
log.info("call routed: callId={}, routeType={}, routeValue={}",
        callId, routeType, routeValue);
```

关键运行节点必须补充中文日志，便于本地联调、生产排障和后续 AI 接手。关键运行节点包括：

- 系统启动和核心模块初始化
- FreeSWITCH XML Curl 请求、匹配、返回
- ESL 连接、认证、订阅、断开、重连
- 呼叫控制命令发送和返回
- 呼入/呼出路由决策
- 坐席签入、签出、示忙、示闲
- 通话状态变化和 WebSocket 推送
- 异步任务开始、完成、失败和重试
- 数据同步、导入导出、外部系统调用

中文日志必须包含可排查上下文，例如：

```text
tenantId
nodeId
callId
channelId
agentId
section
purpose
domain
result
durationMs
```

日志应描述清楚“发生了什么”和“关键上下文是什么”，避免只写“开始执行”“执行完成”这类无上下文日志。

禁止打印：

- 密码
- Sa-Token登录会话和访问令牌
- SIP密码
- ESL密码
- FreeSWITCH directory token
- 完整客户敏感资料
- 未脱敏电话号码
- 完整 ESL 原始事件到普通 INFO 日志
- 完整 FreeSWITCH XML，尤其是包含网关账号、密码或号码资源的 XML
- 完整通话转写、AI 对话和客户隐私内容

涉及敏感响应时，只记录长度、数量、状态、脱敏号码或摘要信息。

### 11.2 指标

至少监控：

- ESL连接状态
- WebSocket连接数
- 坐席在线数
- 当前通话数
- 呼叫成功率
- 呼叫平均时长
- 外呼任务积压
- 队列等待时间
- 事件处理失败数

---

## 12. 异常与安全规范

### 12.1 异常

统一异常类型：

```text
BusinessException
ResourceNotFoundException
ConflictException
ExternalSystemException
```

全局异常处理器负责转换为 API 错误响应。

禁止：

- `catch (Exception)` 后返回成功
- 丢失原始异常原因
- 将数据库异常直接返回前端

### 12.2 安全

必须：

- 使用 BCrypt 或 Argon2 保存密码
- 使用 Sa-Token 管理登录会话、续期、注销和强制下线
- 接口权限和数据权限分离
- 敏感配置使用环境变量或密钥服务
- FreeSWITCH XML Curl 接口限制来源IP或签名
- WebSocket连接进行身份认证
- 客户号码和录音访问需要审计

---

## 13. 数据库规范

### 13.1 表命名

统一业务前缀：

```text
cc_agent
cc_sip_account
cc_sip_gateway
cc_call_route
cc_call_record
cc_outbound_task
cc_outbound_contact
crm_customer
```

### 13.2 公共字段

需要审计的表统一包含：

```text
id
created_by
created_at
updated_by
updated_at
version
deleted
```

### 13.3 约束

必须使用：

- 唯一索引保证业务唯一性
- 普通索引支持常用查询
- 乐观锁保护并发更新
- Flyway维护所有结构变更
- 新增表必须为表和全部业务字段、审计字段提供中文 `COMMENT`
- 枚举代码可以保留英文，字段 `COMMENT` 必须说明对应的中文业务含义
- 数据库迁移脚本、表结构和索引的说明文字统一使用中文

禁止依赖应用代码保证所有唯一性。
禁止新增英文数据库说明。

---

## 14. 测试规范

### 14.1 测试层级

必须覆盖：

- 领域规则单元测试
- Application Service 用例测试
- Mapper集成测试
- ESL事件处理测试
- 关键API集成测试
- 前端核心 Store 和 Hook 测试

### 14.2 呼叫链路测试

至少覆盖：

```text
分机注册
分机互拨
外线呼出
呼入路由
应答
桥接
转接
挂断
重复事件
事件乱序
ESL断线重连
```

### 14.3 合并质量门禁

提交合并前必须通过：

```text
后端编译
后端单元测试
数据库迁移校验
前端类型检查
前端构建
前端测试
静态代码检查
```

新功能没有测试不得合并，除非在评审中明确说明原因和补测计划。

---

## 15. 代码风格

### 15.1 后端

必须：

- 使用构造器注入和 `final` 字段
- 类和方法使用明确业务名称
- 方法尽量保持单一职责
- 对外公开方法编写必要的 JavaDoc
- 枚举代替魔法数字
- 使用 `Clock` 代替散落的系统时间调用，便于测试

禁止：

- 字段注入
- 空的 `catch`
- 超过合理长度的 Service 持续堆积功能
- 在循环中逐条查询数据库
- 使用 `parallelStream()` 处理带数据库或远程调用的逻辑

### 15.2 前端

必须：

- 使用 TypeScript
- API、类型、页面和组件职责分离
- 公共状态进入 Store
- 可复用列表逻辑进入 Hook

禁止：

- 大量 `any`
- 页面直接拼接后端 URL
- 在组件中持久化 Token
- Socket断开导致整个后台不可导航

---

## 16. Git与提交规范

分支建议：

```text
main
develop
feature/*
fix/*
```

提交格式：

```text
feat(outbound): add predictive task creation
fix(esl): handle duplicated CHANNEL_ANSWER event
refactor(call): extract route strategy registry
test(agent): cover agent state transitions
```

禁止将格式化整个项目、无关重构和业务功能混入同一个提交。

---

## 17. 第一阶段开发顺序

严格按照可验证的呼叫链路推进：

1. 初始化管理后台、登录、权限和数据库迁移。
2. 搭建 FreeSWITCH，完成两个分机通过软电话互拨。
3. 实现 SIP账号、坐席和分机管理。
4. 实现 ESL连接、事件接收、呼叫状态模型和通话记录。
5. 实现 WebSocket实时状态推送。
6. 实现坐席工作台和 WebRTC 注册。
7. 实现线路、中继、号码和出局路由。
8. 完成拨打手机和接听外部来电。
9. 实现技能组、队列和呼入分配。
10. 实现客户、名单和预览外呼任务。
11. 实现渐进式和预测式外呼。
12. 实现 IVR、机器人、质检、报表和计费。

每一步必须完成端到端验证后再进入下一阶段。

---

## 18. 新模块开发检查清单

开发前：

- 是否属于现有模块？
- 是否需要新增数据库表和 Flyway 脚本？
- 是否定义了状态、错误码和权限？
- 是否明确同步事务与异步事件边界？

开发中：

- Controller 是否仅负责接口适配？
- 是否使用构造器注入？
- 是否避免魔法数字和 `any`？
- 是否考虑幂等、并发和重试？
- 是否记录必要的结构化日志？

提交前：

- 是否有单元测试或集成测试？
- 是否通过编译、类型检查和构建？
- 是否包含敏感信息？
- 是否更新接口和架构文档？
- 是否验证完整业务链路？

## 19. Dialplan 路由与 IVR 节点扩展规范

号码呼入路由必须通过 `DialplanRouteHandler` 扩展，不允许继续在 `DialplanXmlCurlHandler` 中增加路由类型判断。

- 每种号码路由实现独立的 `DialplanRouteHandler`。
- `routeType()` 必须返回唯一、稳定的大写类型编码。
- 新增路由类型只新增处理器和业务配置，不修改主路由分发流程。
- 未支持的路由类型返回 FreeSWITCH `not found`，并记录中文日志。

IVR 节点必须通过 `IvrNodeCompiler` 扩展，不允许在 IVR 主编译流程中增加节点类型 `switch`。

- 每种节点编译器同时负责自身配置校验和 Dialplan 输出。
- 发布校验与运行时编译必须使用同一个 `IvrNodeCompilerRegistry`。
- 图结构校验、媒体路径解析和公共 XML 输出必须使用独立公共组件。
- 前端节点属性通过 `propertySchema` 声明，并由属性编辑器注册表渲染。
- 新增节点时必须同时实现前端定义、后端编译器、发布校验和真实 FreeSWITCH 验收。
