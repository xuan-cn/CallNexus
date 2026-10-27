# CallNexus 基础部署安装包方案

日期：2026-10-27。状态：已完成服务器只读盘点及本地发行材料采集，尚未构建发行镜像、完成安装包、安装 Docker、迁移数据库、重启或部署服务。

最新约定：数据库 SQL 由用户后续提供，本阶段只预留 `database/init/README.md`，不提取或生成 SQL。UniMRCP 使用现有基础镜像加已替换的插件构建新的发行镜像；FreeSWITCH 保留从环境变量动态渲染配置的机制，并把收音 Lua 固化到发行版本。

## 1. 本次检查范围

- 源服务器 A：192.168.0.176，实际 Compose 工作目录 `/opt/CALLNEXUS`。
- 目标服务器 B：192.168.0.144，已成功通过 SSH 连接。
- 检查容器状态、镜像、挂载、网络、入口脚本、部署文件、JAR 内配置、数据库表元数据及地址配置。
- 不记录 SSH 密码、数据库密码、节点 Token、AI API Key 或数据加密密钥到本文。
- 本文是部署设计，不代表目标机部署或电话链路验收已经通过。

## 2. 两台服务器实测情况

| 项目 | A | B |
| --- | --- | --- |
| 系统 | CentOS Linux 7，内核 3.10，x86_64 | CentOS Linux 7，内核 3.10，x86_64 |
| CPU | 2 核 | 2 核 |
| 内存 | 约 5.8GB | 约 3.1GB |
| 根分区剩余 | 约 14GB | 约 45GB |
| Docker | 26.1.4 | 未安装，命令不存在 |
| Compose | v2.27.1 | 未安装 |
| 应用部署目录 | `/opt/CALLNEXUS` | 未发现该目录 |

A 的主要容器状态：

| 容器 | 状态 | 网络 |
| --- | --- | --- |
| callnexus-server | running / healthy | host |
| mysql8 | running / healthy | host |
| redis7 | running / healthy | host |
| freeswitch | running / healthy | host |
| unimrcp-server | running，无健康检查 | host |
| callnexus-media-agent | running，无健康检查 | bridge |
| callnexus-nginx | running，无健康检查 | host |
| callnexus-snailjob-server | running，无健康检查 | host |
| callnexus-monitor-admin | running，无健康检查 | host |
| callnexus-qdrant | running，无健康检查 | host |
| minio | running / unhealthy，HTTP 健康接口返回 200 | host |
| callnexus-ollama | 已停止 | host |
| openai_api-funasr-api-1 | 已停止，属于另一 Compose 项目 | bridge |

MinIO 的 unhealthy 原因已经确认：容器健康检查使用 `curl`，镜像内没有该命令，日志为 `curl: command not found`。不能把这一状态当作存储服务不可用，也不能把有缺陷的健康检查继续打进新版。

A 在检查时的容器用量约为：主应用 1.13GiB、调度服务 1.23GiB、MySQL 915MiB、监控服务 301MiB，另有语音、存储、向量库等进程。这些是采样值，不能简单相加当作最低物理内存要求。A 的物理内存约 5.7 GiB，另有约 3.9 GiB Swap，当时已用约 486 MiB Swap。8 GB 是预留余量的建议，不是硬性要求。B 约 3 GB 内存下全套服务的稳定性尚未验证，可能需要调整 JVM/MySQL 内存或关闭可选监控组件，并通过实测判断。

## 3. 已发现的迁移关键点

### 3.1 不能只导出原镜像

- 主应用、调度、监控的 JAR 均从宿主机 `app/` 挂载，不在 Java 基础镜像中。
- 前端 `frontend/dist`、Nginx 配置、FreeSWITCH 入口及上传脚本来自宿主机。
- UniMRCP 的 `callnexusrecog.so`、`callnexussynth.so` 已在容器可写层替换；旧镜像导出包不包含这两项更新。
- FreeSWITCH 的 `callnexus_detect_speech.lua` 也在容器可写层修改，现有 Compose 没有将该脚本作为独立持久挂载。
- 当前 `mod_unimrcp.so` 位于 FreeSWITCH 镜像中，需要锁定与插件匹配的镜像版本。

现有离线镜像文件与对应 FreeSWITCH/UniMRCP 原始镜像 ID 相符，但这只证明原镜像一致，不代表包含容器运行后的修改。发行物必须包含“基础镜像 + 经确认的插件/脚本补丁 + 外挂应用文件”。不直接 `docker commit` 运行容器，以免混入运行状态或凭据。

### 3.2 磁盘插件和进程实际加载版本可能不同

磁盘上的识别插件包含新版 `vad_level` 日志标记，与宿主机保存的识别插件哈希一致。但 UniMRCP 进程的 `/proc/1/maps` 中，识别插件显示 `(deleted)`，表示进程仍映射着已被替换掉的旧 inode。

本次未重启 A。制作发行包时应明确选择并记录插件版本，在 B 的新进程中验证实际加载文件的哈希与日志，不能直接声称 A 当前进程已经使用磁盘上的新版。

### 3.3 `.env` 尚未覆盖所有配置

- FreeSWITCH 镜像环境中残留 `192.168.244.128` 的本地 IP/SIP 域默认值。
- 当前 SIP Profile、UniMRCP 服务及客户端配置中有 A 的地址。
- 主应用 JAR 的 Qdrant 默认地址仍指向 A。
- Java 生产配置中的数据库、Redis、SnailJob 连接参数不能仅依靠修改 Compose 的 MySQL 环境变量实现联动。
- 数据库 `cc_freeswitch_node` 的 SIP 域、WSS、ESL 地址指向 A。
- 数据库多条 `sys_oss_config` 的 MinIO endpoint 指向 A。
- 前端 bundle 中命中的 A 地址经上下文核实仅为输入框 placeholder，不是已确认的网络连接地址。仍应去除这类环境专属示例，实际部署使用相对 API 路径或运行时配置。

Compose `.env` 提供插值来源，必须通过 `environment`、`env_file` 或配置生成显式传入应用；不能假设文件里的变量会自动覆盖容器内所有配置。[Docker 环境变量说明](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)

### 3.4 现有部署缺少空库初始化材料

当前实际数据库是 `callhub`，共有 207 张表；MySQL 容器 `/docker-entrypoint-initdb.d` 未发现初始化文件。因此，只复制 Compose 和镜像，在空数据卷上无法复现当前系统。

需要独立整理与当前 JAR 配套的表结构、升级版本及必要种子数据。SnailJob 的任务日志、批次、执行记录已有大量运行数据，不应随基础包导出。

## 4. 建议发行形式

默认制作全新的“基础安装包”，包含全部必需服务及初始化材料，不复制 A 的数据库数据目录或 Docker 数据卷。

```text
callnexus-release-<version>-linux-amd64/
  compose.yaml
  .env.example
  manifest.json
  SHA256SUMS
  README.md
  images/                 # 9 个必需镜像及其 ID/摘要
  app/                    # 主应用、SnailJob、Monitor 三个 JAR
  frontend/dist/
  config/templates/       # Java、Nginx、FreeSWITCH、UniMRCP 模板
  patches/
    unimrcp/              # callnexusrecog.so、callnexussynth.so
    freeswitch/           # callnexus_detect_speech.lua 等经核实的脚本
  database/
    init/                 # 用户后续提供 SQL，首次空库按文件名顺序执行
      README.md
  scripts/
    preflight.sh
    load-images.sh
    init-secrets.sh
    render-config.sh
    bootstrap.sh
    doctor.sh
  prerequisites/          # 可选：目标 OS 对应的离线 Docker/Compose 依赖
  optional/               # Ollama、模型文件或其他经选择的可选组件
```

首次安装流程：解压 → 准备 Docker → 填写 `.env` → 校验 → 导入镜像 → 生成配置与密钥 → 初始化空库/存储 → 启动全部服务。

建议入口为 `./scripts/bootstrap.sh`；完成首次初始化后可直接使用 `docker compose up -d` 启停。首次初始化可由 Compose 的一次性 init 服务编排，初始化脚本必须识别已有数据库，不能覆盖或清空已安装系统。

必需服务按 A 当前实际运行的 11 个服务整理，Java 基础镜像由三个 Java 服务共享，共 9 个必需镜像。Ollama 停止且其镜像压缩包约 3.23GB，建议单列可选组件；其他 Compose 项目的 FunASR 不默认混入。

现有 `images/` 约 3.8GB，其中大部分来自 Ollama。基础镜像压缩包加 JAR/前端的原始材料约为 1.1GB 量级，最终大小以完成清理、补丁归档后的校验清单为准。制作包时不能把这项估算当作交付文件大小。

## 5. 环境变量与地址设计

同一个地址不能同时承担监听、对外广播、浏览器访问和内部服务互连四种职责。

| 变量组 | 建议变量 | 用途 |
| --- | --- | --- |
| 部署 | `COMPOSE_PROJECT_NAME`、`DATA_ROOT`、`TZ` | 项目、持久目录、时区 |
| 主机 | `HOST_BIND_IP`、`PUBLIC_HOST`、`APP_PUBLIC_URL` | 本机网卡与外部入口 |
| SIP | `SIP_DOMAIN`、`FS_SIP_BIND_IP`、`FS_SIP_ADVERTISE_IP` | SIP 域、监听、信令对外地址 |
| RTP | `FS_RTP_BIND_IP`、`FS_RTP_ADVERTISE_IP`、`FS_RTP_PORT_MIN/MAX` | 媒体监听、SDP 广播、范围 |
| SIP 端口 | `FS_INTERNAL_SIP_PORT`、`FS_EXTERNAL_SIP_PORT`、`FS_WSS_PORT` | 注册、中继、WebRTC |
| ESL | `FS_ESL_HOST`、`FS_ESL_PORT`、`FS_ESL_PASSWORD`、`FS_ESL_ALLOWED_CIDRS` | Java 控制 FreeSWITCH |
| 内部 API | `APP_INTERNAL_URL`、`CALLNEXUS_API_BASE_URL`、`TTS_INTERNAL_WS_URL` | 上传、任务领取、ASR、TTS |
| MRCP | `UNIMRCP_HOST_IP`、`UNIMRCP_SIP_PORT`、`UNIMRCP_MRCP_PORT` | FreeSWITCH 与 UniMRCP 互连 |
| MRCP 媒体 | `MRCP_CLIENT_RTP_MIN/MAX`、`MRCP_SERVER_RTP_MIN/MAX` | 两端媒体范围，避免相互重叠 |
| 节点 | `CALLNEXUS_NODE_CODE`、`CALLNEXUS_NODE_TOKEN`、`CALLNEXUS_TENANT_ID` | 三方共同使用的节点身份 |
| 数据库 | `MYSQL_HOST/PORT/DATABASE/USER/PASSWORD`、`MYSQL_ROOT_PASSWORD` | 同步生成 MySQL 与 Java 连接配置 |
| Redis | `REDIS_HOST/PORT/PASSWORD` | 同步生成服务端与客户端配置 |
| 存储 | `OSS_INTERNAL_ENDPOINT`、`OSS_PUBLIC_BASE_URL`、`MINIO_ROOT_USER/PASSWORD` | 后端连接与浏览器可访问地址分离 |
| 调度 | `SNAIL_JOB_SERVER_HOST/PORT`、`SNAIL_JOB_NAMESPACE/GROUP/TOKEN` | 服务与客户端保持一致 |
| AI | `QDRANT_URL`、ASR/TTS 超时及 VAD 参数 | 消除源机器回退地址 |
| 密钥 | `CALLNEXUS_DATA_ENCRYPT_KEY`、Directory Secret、实时令牌密钥 | 首次生成；后续升级不得重新随机覆盖 |
| TLS | `HTTPS_CERT_FILE`、`HTTPS_KEY_FILE`、SIP/WSS 证书路径 | 按部署域名提供或生成测试证书 |

变量名是发行包拟定接口，实施时需建立到当前 Java 属性、数据库字段、XML 参数的明确映射，不能假设增加一个同名变量就已经被组件支持。

推荐单机版保留语音服务的 host 网络。其余现有 host 服务暂不为打包而大幅改架构；media-agent 需要明确处理：若统一为 host，可以访问本机回环 API；若保留 bridge，必须配置可达的宿主机地址，不得直接使用容器自身的 `127.0.0.1`。[Docker host 网络说明](https://docs.docker.com/engine/network/drivers/host/)

渲染时只替换明确定义的安装包占位符，不对整个文件无差别执行变量替换，以免破坏 FreeSWITCH `$${...}`、Spring `${...}`、Nginx `$host/$uri` 等组件自身语法。

`.env.example` 不填写 A 的地址或凭据。安装时必填地址留空并校验，缺少公网地址时不得悄悄回退到 A。生成后的配置里出现目标机器 IP 是正常现象，“不写死”指发行源模板可通过环境变量迁移，而非禁止运行配置出现 IP。

## 6. 云服务器模式

支持 LAN 与云/NAT 两种安装参数配置，使用同一套模板，不为每个 IP 重新编译 JAR 或镜像。

- 内网部署：监听 IP 与对外广播 IP 通常可以相同。
- 云服务器：SIP/RTP 监听云主机实际拥有的内网网卡 IP，外部 SIP/SDP 广播配置为公网 IP；公网 IP 不在网卡上时不能拿来绑定监听。
- 同机 FreeSWITCH、UniMRCP、Java 优先使用可达回环或本机内网通信，MRCP 不使用公网绕行。
- 浏览器入口、WSS 和对象存储公共地址使用实际可访问的域名及证书；内部存储 endpoint 不等同于浏览器下载地址。
- SIP 域可以是域名，与网卡 IP 分别配置；B 的节点注册域不能默认为 A 的 SIP 域。
- 显式配置 MRCP 地址，不依赖当前脚本排除 `10.*` 的自动探测逻辑；云主机恰好可能使用 10 段内网。
- 首次安装不得带入 A 的注册绑定、网关运行态或自动向原运营商发起注册。迁移线路凭据属于后续开通步骤。

建议端口策略：

| 用途 | 端口/范围 | 访问范围 |
| --- | --- | --- |
| Web/API | 80/443，按入口配置 | 用户或反向代理 |
| SIP 注册 | 默认 5060 UDP/TCP，按实际使用 | 分机来源/办公网络 |
| SIP 中继 | 默认 5080 UDP/TCP，按实际使用 | 运营商或 SIP 对端 |
| SIP TLS | 按启用的 TLS Profile 端口 | 按需开放，非必需全部开放 |
| WSS | 当前 7443 TCP | 使用 WebRTC 时开放并配置证书 |
| 电话 RTP | 显式设置的 `FS_RTP_PORT_MIN/MAX` UDP | 实际媒体对端；范围与配置一致 |
| MRCP 信令 | 当前 8060、1544，其他 MRCPv1 端口仅启用时需要 | 同机/内网，不向公网暴露 |
| MRCP RTP | 当前客户端 14000–14999、服务端 15000–15999 UDP | 同机/内网 |
| ESL | 8021 TCP | 优先本机；多机时指定私网来源 |
| 数据库、Redis、Qdrant、调度、监控 | 3306、6379、6333/6334、17888、28080、8800、9090 等 | 不作为公网业务入口 |
| MinIO | 内部 9000，控制台 9001 | 文件通过配置的公网域名反代；不直接开放管理端口 |

当前 FreeSWITCH `switch.conf.xml` 的 RTP 起止参数是注释，不能把“示例范围”当作已核实的运行范围。安装包应显式写入选择的范围，并用相同值生成主机防火墙/云安全组说明。host 网络下 Compose `ports` 不承担隔离作用，须靠服务监听配置和主机/云侧访问控制。

对象存储建议使用专用文件域名反代，保留签名所需的路径和 Host；不能为了隐藏 9000 随意增加路径前缀，造成预签名 URL 失效。FreeSWITCH/UniMRCP 不需要将 ESL/MRCP 端口公开到互联网。

## 7. 数据与配置保留规则

后续用户提供的 SQL 应包含表结构、必要字典、菜单、权限、系统参数、初始化管理员及其关联关系，并与当前应用版本匹配。本阶段仅预留目录，不导出这些数据或生成脚本。管理员密码在目标环境初始化，原服务器用户和密码哈希不整库复制。

节点、OSS、调度命名空间/分组、必要后台任务等必须与目标 `.env` 联动初始化。节点 Token 的哈希和配置加密须复用当前系统规则，不能把明文随意塞入数据库。

不包含：客户、工单、通话、录音、ASR dump、AI 对话、质检结果、外呼名单、调度执行历史、会话租约、在线状态、注册缓存、Redis RDB/AOF、Qdrant 业务索引、MinIO 用户对象、日志、临时文件、旧备份和源机私钥。

AI 助手、编排流程、意图模板、评分模板等如需作为行业演示配置，单独制作“可选配置种子包”，保留引用关系但移除源客户绑定、线路凭据和 API Key。必要固定提示音按白名单随包携带，并在目标环境重建媒体元数据；不把运行期声音目录整体复制。

基础包生成新加密密钥后，不能直接沿用 A 上旧密钥加密的服务商凭据。若未来需要私有的完整配置迁移，应另设计受控导入/重新加密流程，不与可分发基础安装包混用。

## 8. 制作、安装及验收顺序

1. 确认发行基线：A 的三个 JAR、前端、镜像 ID、两个 UniMRCP 插件、Lua 脚本分别计算 SHA-256。记录哪些是磁盘版本、哪些已实测运行。
2. 将必要文件导入本地或独立发行暂存区，以白名单组包，A 保持不变。对二进制、脚本、镜像、SQL、模板生成统一 manifest。
3. 预留 `database/init/`，等待用户提供匹配的 SQL；不在 A 导出数据库或执行清理 SQL。
4. 整理占位符模板及环境映射，修正健康检查、源码专属默认地址、插件/脚本挂载与空卷初始化问题。
5. B 先准备内存与 Docker；针对实际 OS/CPU 提供匹配运行时，不默认在老系统上安装最新 Docker。
6. 在 B 使用新数据目录和数据卷初始化。预检端口、目录、权限、变量、镜像校验、数据库版本；已有系统默认拒绝重新初始化。
7. 依次完成数据库/Redis/存储准备、建表与种子、应用及调度启动、节点与桶初始化、语音组件启动、Nginx 入口。
8. 验证后台登录、菜单、调度客户端注册、ESL 连接、节点心跳、媒体任务；验证两个插件及 Lua 的实际加载路径/哈希。
9. 配置目标环境的 AI 凭据、测试分机和测试线路后，验证双向语音、ASR/TTS、打断控场、流程推进、录音上传及回放。
10. 验证换 IP 后只改环境配置并重建对应容器即可；只修改 `.env` 后执行 `docker compose restart` 不会更新容器环境，应通过 `docker compose up -d` 重建受影响服务。

不对 A 或 B 执行 `docker compose down -v`。未来安装脚本应把首次安装、升级和卸载分开，升级保留密钥、数据和迁移版本，不重新初始化。

安装文件可以离线导入，但调用阿里云等云 ASR/TTS/Chat 服务仍需要对外网络及有效凭据；离线安装不等于云 AI 可离线运行。

## 9. 本阶段交付与下一阶段

本阶段已经完成 A/B 连接与只读检查，形成本方案及本地脱敏盘点记录。本地 `release-work/2026-10-27/` 已收集 9 个基础镜像归档、前端、三个原始 JAR、必要原始模板、两个 UniMRCP 插件和收音 Lua，并生成逐文件来源与 SHA-256 清单。原始 JAR/配置与候选发行目录隔离，避免将未清理的配置直接分发。

2026-10-27 后续进展：已在 A 的独立 `/opt/CALLNEXUS-release-build/20261027` 构建两个新标签并导出，不替换或重启生产容器：`callnexus-unimrcp-server:1.8.0-release.20261027`、`callnexus-freeswitch-unimrcp:1.10.12-release.20261027`。已通过隔离、无网络容器核对插件/Lua 哈希和动态依赖。

候选目录已补充 `.env.example`、`compose.yaml`、193 个 XML 模板、配置生成/首次安装/诊断脚本。三个 JAR 仅改写应用配置资源，业务类与依赖不变；旧密码/密钥改为环境变量，前后端接口密钥配对在安装时生成。数据库仍只预留目录，不生成 SQL。

已完成配置渲染、重复渲染、XML 解析、Compose、Shell 语法、Nginx 配置、JAR 完整性检查及应用配置凭据扫描。基础镜像历史层不在应用凭据扫描范围内，不声称完成完整镜像安全审计。安装器阻止缺失 SQL 或端口冲突时首次安装；内存低于推荐容量仅提醒，不硬性拦截。未在 B 安装 Docker 或启动服务。

下一阶段用户补充匹配版本的初始化 SQL，准备 B 的 Docker/Compose 并评估内存参数，再进行空库安装、登录、节点/OSS/调度与真实通话验收。8 GB 是容量建议而非必需条件；A 采集时物理内存约 5.7 GiB，另有约 3.9 GiB Swap。操作说明以候选目录 `README.md` 为准，完成验收后才能标记为已验收发行版本。

## 10. B 手工验证进展

用户反馈已将 B 调整到约 6 GB 内存，并完成 Docker 26.1.4、Compose v2.27.1、Python 3.6.8 和 unzip 安装；Docker 为 active，尚无业务容器。Python 标准库导入检查通过。

RC2 调整配置工具以兼容 Python 3.6：移除新版 `Path.write_text(newline=...)` 和 `argparse.add_subparsers(required=...)` 用法。已进行本地语法、旧 API 限制、初始化/重复渲染和密钥防覆盖检查，尚待用户在 B 的实际 Python 3.6 下执行验证，不将本地检查称作目标机实测。

候选目录已出现用户提供的 `database/init/001-schema.sql.sql`，有 207 张表结构，含 DROP TABLE、登录日志种子及旧地址。保持用户原文件不变，SQL 审阅和目标环境替换尚未完成，暂不导入。发行清单改为按实际文件记录 SQL 是否随包提供，不再把预留目录状态写死为无 SQL。

后续数据库准备：只读比较 A 的列名清单，原 SQL 有 3894 列，A 有 3895 列，差异为 `cc_outbound_task_retry_rule.active_result_code`。当前 JAR 自带 V160 修复脚本，且 Flyway 自动迁移关闭，因此必须显式补充该修复，不依赖应用启动自动补表。

RC3 保留原 SQL，另生成 `database/reviewed/` 模板，移除 1 条登录日志 INSERT 和 13 条旧 OSS INSERT，将 2 条调度分组 Token 改为占位符。`scripts/prepare_database.py` 在 B 上读取 `.env` 并生成 `runtime/database/init/` 两个文件；MySQL 改为只挂载该生成目录。该脚本校验原始/模板/迁移 SHA-256，不连接数据库，生成文件权限为 600，不打印 Token。已通过本地生成、重复生成、原文件保护和变更拒绝测试，目标机 Python 执行与 MySQL 导入仍待用户验证。

RC4 修正首次启动权限：B 的 MySQL entrypoint 降权到 mysql 后无法读取 root 所有的生成目录。现先加载镜像，再动态查询镜像内 mysql UID/GID，将生成目录归该用户所有、权限 700，SQL 文件权限 400；不使用 777，不关闭 SELinux，不删除数据卷。已在 A 的独立临时目录用相同镜像的非 root 用户复现失败并验证修复，未启动测试 mysqld，未挂载生产数据。B 仍由用户按步骤调整权限和验证初始化。

RC5 更正 SQL 比较范围：之前只比较 CREATE TABLE 片段，完整原 SQL 的末尾实际已包含与当前 JAR 相同的 V160 ALTER。RC3/RC4 的第二个脚本重复执行该修复，导致 B 报 1091 后重启，health 为 healthy 不能证明脚本均成功。构建器现对完整语句做规范化比较，主脚本已有同一修复时，第二个文件只输出注释，不重复 DDL。保留原文件；B 当前库先只读检查 active_result_code 和 uk_cc_outbound_retry_rule_active，不清库重建。
