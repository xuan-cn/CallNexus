# 后端开发与部署配置

## 配置边界

- `application.yml`：公共配置；保留原开发接口加密密钥的默认值，兼容现有前端。
- `application-dev.yml`：保留现有开发连接默认值，支持环境变量覆盖。
- `application-prod.yml`：读取安装包 Compose 已使用的环境变量；核心凭据没有开发值兜底。
- `.env.dev.example`：本地填写示例，不含服务器真实密码。
- 服务器部署目录 `.env`：目标服务器自己的配置，由 Compose 注入，升级时保留。

这些修改不改变数据库、分机或服务器正在运行的服务。原有 Flyway 开关不变；升级需要的 SQL 仍须单独审核执行。

## IDEA 本地运行

1. 保持 Maven `dev` profile（当前默认），刷新 Maven 项目。
2. 打开启动类的运行/调试配置，在“环境变量”中设置 `SPRING_PROFILES_ACTIVE=dev`。
3. 保留原先的 `CALLNEXUS_DATA_ENCRYPT_KEY`；它必须对应当前开发库中已有的加密数据。
4. 数据库、Redis 等配置需要调整时，按下表添加环境变量；不调整则继续使用原开发配置。

可以复制 `.env.dev.example` 为 `.env.dev` 整理本机值。Spring Boot 不会自动读取 `.env.dev`；如果 IDEA 版本支持环境文件，可在运行配置中选取，否则将有效的 `KEY=value` 项填写到环境变量编辑器。不要将示例提示文字当成真实密钥。

不要将 B 的整份 `.env` 导入 IDEA：数据库地址、数据加密密钥、调度组令牌和前后端加密密钥可能与开发环境不同。

## 变量对照

| 变量 | 对应配置 | 开发环境 | 生产环境 |
| --- | --- | --- | --- |
| `CNX_JDBC_URL` | 主库 JDBC URL（完整地址与参数） | 可选，默认原开发库 | 必填，由 Compose 构造 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | 主库用户名/密码 | 可选 | 必填 |
| `REDIS_HOST` / `REDIS_PORT` | Redis 地址/端口 | 可选，默认原开发地址/6379 | 可选，默认 127.0.0.1/6379 |
| `REDIS_PASSWORD` | Redis 密码 | 可选 | 必填 |
| `CNX_SNAIL_TOKEN` | SnailJob 组认证令牌 | 可选，默认原开发组令牌 | 必填，与调度后台一致 |
| `CNX_LOCAL_IP` | 调度服务地址与客户端上报 IP | 可选 | 默认 127.0.0.1，适合当前 host 网络单机部署 |
| `CNX_MONITOR_USER` / `CNX_MONITOR_PASSWORD` | 监控客户端账号/密码 | 可选，默认 Maven 开发值 | 由现有 Compose 提供 |
| `CNX_MONITOR_ENABLED` / `CNX_MONITOR_URL` | 是否注册监控/监控地址 | 开发维持原配置 | 默认 false / http://127.0.0.1:9090/admin |
| `CALLNEXUS_DATA_ENCRYPT_KEY` | 数据库敏感凭据加密密钥 | 必填，保留原值 | 必填，保留目标 `.env` 原值 |
| `CNX_API_REQUEST_PRIVATE_KEY` | 后端请求解密私钥 | 可选，保留原开发值 | 必填，与部署前端请求公钥配套 |
| `CNX_API_RESPONSE_PUBLIC_KEY` | 后端响应加密公钥 | 可选，保留原开发值 | 必填，与部署前端响应私钥配套 |
| `CNX_JWT_SECRET` | 登录签名密钥 | 可选，保留原开发值 | 必填 |
| `CNX_REALTIME_TOKEN_SECRET` | 实时语音令牌签名密钥 | 可选，兼容旧变量名 | 必填 |
| `CALLNEXUS_FREESWITCH_DIRECTORY_SECRET` | 动态目录接口共享密钥 | 可选，保留原开发值 | 必填，与 FreeSWITCH 对应配置一致 |
| `CALLNEXUS_AI_QDRANT_URL` | 向量库地址 | 可选，保留原开发地址 | 必填 |
| `QDRANT_API_KEY` | 向量库密钥 | 可选，兼容 `CALLNEXUS_AI_QDRANT_API_KEY` | 必填 |

## 后续打包更新

在仓库根目录执行（需要 JDK 17 和 Maven）：

```bash
mvn -Pprod -pl callnexus-admin -am package -DskipTests
```

生成 `callnexus-admin/target/callnexus-admin.jar`。生产凭据不需要在构建时传入，运行时由 Compose 注入；不要把真实值填进 YAML 或 Maven profile。

当前安装包已有这些变量，无需替换其 `.env`。备份原 `app/callnexus-admin.jar`，在无通话时停止 `server`，替换 JAR，然后执行：

```bash
docker compose up -d --no-deps --force-recreate server
docker compose logs --tail=100 server
```

后端重启会中断正在进行的 AI 会话。不要重跑初始化 SQL、`configure.py init`，不要删除数据卷。新版本有结构变化时另行备份数据库并执行增量 SQL。

## 验证

配置单元测试不连接数据库或 Redis。已在本机安装项目依赖时，可以只运行入口模块：

```bash
mvn -Pdev -pl callnexus-admin test "-Dtest=BackendEnvironmentConfigurationTest" "-DskipTests=false"
```

上线后仍需验证登录、ESL 重新连接、文件上传和 AI 通话；本地配置测试不替代服务器验收。
