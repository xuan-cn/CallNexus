# 内网与云公网安装部署手册

适用：本包的 Linux x86_64、Docker Compose 单机 host 网络部署。不适用多机拆分、Kubernetes、已有数据环境的直接重装。脚本不会安装 Docker、修改安全组/防火墙、修改 SSH 密码或自动配置 HTTPS 证书。

## 1. 安装前准备

### 1.1 确认主机和数据

在目标主机执行：

```bash
uname -m
free -m
df -h /opt
docker --version
docker compose version
python3 --version
openssl version
ip -4 addr show scope global
ip -4 route get 1.1.1.1
docker ps -a
docker volume ls
```

依赖：Docker、支持 Compose Specification 的 Compose 插件、Python 3.6+、OpenSSL、unzip、gzip、sha256sum、curl。旧 Python 兼容不等于仍受安全维护。内存推荐约8GB，不是硬性门槛；已验证的低并发环境约6GB且有Swap，实际容量按并发、录音和AI负载评估。磁盘需覆盖安装包、展开镜像和持续增长的业务文件。

没有容器不代表没有数据。准备脚本拒绝使用已有项目容器/数据卷的项目名，防止新密码与旧 MySQL 账户不一致。需保留旧数据时走升级，不运行本手册的首次安装；需清理时由管理员先备份并确认具体对象，不使用全局 prune。仅更换项目名可以隔离本包命名卷，但不能解决 host 网络端口被旧服务占用的问题。

### 1.2 上传、校验、解压

上传安装 ZIP 及其 `.sha256`，进入上传目录，按实际文件名执行：

```bash
sha256sum -c callnexus-deploy-20261028-final-r3.zip.sha256
unzip callnexus-deploy-20261028-final-r3.zip -d /opt/callnexus-new
cd /opt/callnexus-new/callnexus-deploy
sha256sum -c SHA256SUMS --quiet
```

必须使用新的解压目录，不覆盖正在运行的版本。校验失败先重新传输/核对版本，不忽略后继续执行。

## 2. 推荐：交互式准备

```bash
python3 -B scripts/prepare_install.py
```

按提示填写：

| 项目 | 含义与建议 |
| --- | --- |
| 项目名 | 默认 callnexus，决定容器/数据卷名称；安装后不要随意改 |
| 部署方式 | lan 内网；cloud 云公网/NAT |
| 本机 IPv4 | 必须存在于网卡；不要填 docker0/容器网桥地址；云服务器通常填私网地址 |
| 公网 IPv4 | cloud 模式填写云控制台公网/弹性公网IP，不自动通过外部网站推断 |
| SIP 认证域 | 默认内网/公网IP，或自行输入已配置的域名；后台节点、分机和软电话一致 |
| Web端口 | 默认80；冲突端口会在后续预检拒绝 |
| MySQL数据库/账号 | 空数据卷首次启动创建；不是系统业务管理员账号 |
| MinIO账号 | 管理AccessKey，不是业务登录账号 |
| 四项服务密码 | MySQL root、MySQL业务、Redis、MinIO；可统一或分别设置；回车使用自动随机值 |
| ESL密码 | 自定义或保留随机值，后台节点需要填相同密码 |
| 管理工具方式 | local：MySQL/MinIO控制台仅回环；lan：MySQL额外监听私网IP，MinIO控制台监听私网IP |

密码输入隐藏且要求确认。为避免 `.env` 与 Compose 转义冲突，密码支持8至128位字母、数字及 `! @ % _ + = . , : / -`；不支持空格、单/双引号、美元符、井号、反引号。建议正式环境使用不同的12位以上强密码。密码不通过命令行参数传递，不打印到日志。

确认摘要后输入 `YES`，才会保存 `.env`（权限600，中文注释）并生成 `runtime/config/` 与配套前端。未确认时不写文件。脚本不启动容器、不加载镜像、不写数据库。内部数据加密密钥、RSA密钥和签名密钥自动生成，无需手工填写。

如果已生成 `.env`，但还没启动任何服务、也没有该项目数据卷，需要调整安装前参数时：

```bash
python3 -B scripts/prepare_install.py --edit-before-install
```

该模式会备份原 `.env` 到 `runtime/backups/`，只允许改安装参数与指定服务密码；数据加密/RSA/签名/调度令牌等内部密钥不变，未知扩展项保留。已有项目容器或卷会拒绝，不能用它给运行中的系统轮换数据库密码。若中途生成失败，`.env` 可能已保存，修正问题后执行 render，不要重新 init。

## 3. 内网部署

例如网卡地址为 `192.168.10.10`，选择 lan；脚本生成：

```dotenv
FS_BIND_IP=192.168.10.10
UNIMRCP_HOST_IP=192.168.10.10
FS_SIP_ADVERTISE_IP=192.168.10.10
FS_RTP_ADVERTISE_IP=192.168.10.10
SIP_DOMAIN=192.168.10.10
```

同机内部服务继续使用 `127.0.0.1`。内网也不代表可信：SIP注册必须验证密码，管理端口应限制来源。

不使用交互脚本时，可手工生成后编辑 `.env`：

```bash
python3 scripts/configure.py init --bind-ip 192.168.10.10
```

注意低级 `init` 默认把 MinIO 控制台绑定私网IP；交互脚本默认 local 管理模式更保守。手工修改密码须在首次启动前完成。

## 4. 云公网部署

例如私网 `10.0.4.6`、公网 `110.40.214.144`：交互脚本选择 cloud，本机填私网，公网填云控制台地址，生成：

```dotenv
FS_BIND_IP=10.0.4.6
UNIMRCP_HOST_IP=10.0.4.6
FS_SIP_ADVERTISE_IP=110.40.214.144
FS_RTP_ADVERTISE_IP=110.40.214.144
SIP_DOMAIN=110.40.214.144
CNX_LOCAL_IP=127.0.0.1
CALLNEXUS_API_BASE_URL=http://127.0.0.1:8080
CALLNEXUS_AI_QDRANT_URL=http://127.0.0.1:6333
MYSQL_BIND_ADDRESSES=127.0.0.1
MINIO_CONSOLE_BIND_IP=127.0.0.1
```

上面仅为本次主机示例，换服务器必须改成实际地址。公网IP通常是NAT映射，不能作为 FreeSWITCH 本机 bind 地址。若本机网卡直接拥有公网IP，按实际网卡和网络拓扑配置。VPN、专网线路或多出口情况下，宣告地址需单独设计，不盲目宣告公网。

低级手工方式：

```bash
python3 scripts/configure.py init --bind-ip 10.0.4.6 --public-ip 110.40.214.144
```

### 4.1 安全组与主机防火墙

| 端口/协议 | 用途 | 来源策略 |
| --- | --- | --- |
| TCP 22 | SSH | 仅可信管理员IP |
| TCP HTTP_PORT（默认80） | 后台初始访问 | 部署期限制管理员IP；正式启用HTTPS |
| TCP 443 | HTTPS入口 | 仅在确实配置证书/反代后开放，安装包不自动完成 |
| UDP 5060 | 分机SIP注册 | 实际终端来源；TCP注册按终端/Profile实际支持另配 |
| UDP 5080 | 外线SIP | 实际运营商/中继来源，不是所有场景都需要 |
| UDP FS_RTP_MIN至MAX（默认16384–32768） | 电话音频 | 按终端和线路媒体来源放行 |
| TCP 7443 | 当前Profile的WSS | 仅WebRTC需要，先完成受信证书/HTTPS及地址配置 |
| TCP 9000 | MinIO S3与文件访问 | 按需限来源，建议通过受控HTTPS文件域名反代 |

不要公开8021、8060、1544、1554、内部MRCP RTP范围14000–15999、3306/33060、6379、6333/6334、8080、8800、9090、17888、28080、9001。用户需要Navicat时优先SSH隧道；选择lan模式也必须自行限制访问来源。Redis仍默认仅回环，不因选择lan而开放。

只开放SIP而未开放电话RTP会导致无声/单向声音。仅设置公网IP不会自动配置云NAT、安全组、主机防火墙、DNS或TLS；浏览器WebRTC不能仅靠HTTP和放行端口使用。

## 5. 首次启动

```bash
bash scripts/install.sh
bash scripts/install.sh --start
docker compose ps
docker compose logs --tail=100 mysql server
bash scripts/doctor.sh
```

安装器顺序：生成配置与校验 → 端口检查 → 加载11个离线镜像 → 准备MySQL初始化SQL及用户权限 → 启动基础服务 → 等待MinIO并创建9个私有空桶 → 启动其余服务。数据库SQL只在空卷执行，健康状态不代表每条初始化SQL都执行成功，必须查看完整初始化日志。

原SQL不随包分发；默认使用审阅模板，避免重复V160。`database/init/` 为自定义初始化预留目录，换SQL需重新审阅，不直接混用。遇到错误先保留日志分析，不删除卷、反复重跑初始化。

## 6. 后台配置与验收

1. 使用初始化脚本提供方确认的初始登录方式，登录后修改系统管理员密码。基础服务密码不是系统管理员密码。
2. 文件管理配置OSS：9000是文件接口，9001是控制台；创建桶不等于完成OSS配置。下载地址必须能被用户浏览器访问，不能给浏览器返回127.0.0.1。
3. 新增FreeSWITCH节点，使用对应SIP域；ESL填127.0.0.1:8021，密码从`.env`的`FS_ESL_PASSWORD`填写。
4. 启用节点及媒体代理，将后台生成的节点编码、媒体代理令牌分别填回`.env`的`CALLNEXUS_NODE_CODE`和`CALLNEXUS_NODE_TOKEN`；租户一致。新包UniMRCP默认复用，旧`UNIMRCP_NODE_*`若存在须同步，不能保留另一组随机值。
5. 无通话时重新render并重建相关容器，配置调度组/任务、分机、线路、AI服务商和流程。
6. 验证正确/错误密码注册、双向音频、呼入呼出、ASR/TTS、多轮对话、节点打断、流程流转、录音上传播放下载。

## 7. 变更与排障

修改 `.env` 不会自动更新运行配置；`restart` 不读取新容器环境。按修改范围操作：

| 修改内容 | 后续动作 |
| --- | --- |
| ESL密码、SIP/RTP地址、XML参数 | `python3 scripts/configure.py render`，无通话时重建freeswitch，ESL密码同步后台 |
| 节点编码/媒体代理令牌 | 重建freeswitch、media-agent、unimrcp，旧UNIMRCP变量也要一致 |
| 新前端/新JAR | 成对备份/替换，render后重建server、nginx；刷新浏览器缓存 |
| 已有MySQL密码 | 先通过数据库管理流程修改持久化账户，再同步`.env`和相关容器；禁止只改env |
| Redis密码 | 修改env后重建redis和server，在维护窗口处理 |
| MinIO凭据 | 重建minio，同时同步后台OSS与其他客户端，不能只改env |
| 数据加密/RSA/签名密钥 | 正常升级保持不变，不用准备脚本轮换 |

ESL排错（不需要开放公网）：

```bash
docker compose exec freeswitch fs_cli -H 127.0.0.1 -P 8021 -p '实际ESL密码'
```

生产密码尽量避免写入共享终端记录，日志截图不要显示密码。出现`.env`正确、XML仍旧时，先render再重建freeswitch。登录报RSA解密失败时先无痕窗口/强制刷新；不直接更换密钥或关闭接口加密。ASR返回节点鉴权失败时先核对节点编码/媒体代理令牌、租户、启用状态，不先调VAD或超时。

已部署环境升级与回滚详见 `OPERATIONS.md`；脚本完整说明见 `SCRIPTS-REFERENCE.md`。
