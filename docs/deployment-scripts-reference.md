# scripts 脚本说明

所有命令从解压后的 `callnexus-deploy` 目录执行。示例均不包含真实密码。脚本不等于完整功能验收；`__pycache__`不是脚本，不分发。

## 快速选择

| 脚本 | 用途 | 何时用 | 调用方式 | 是否改变服务/数据 |
| --- | --- | --- | --- | --- |
| `prepare_install.py` | 交互填写部署方式、IP、账号、密码，安全生成env和配置 | 推荐首次准备 | `python3 -B scripts/prepare_install.py` | 写私有env及runtime，不启动服务、不写库 |
| `configure.py` | 底层环境初始化、渲染、补中文注释 | 手工准备/变更配置/旧env补注释 | 见下文三个子命令 | 写配置，不自动重启 |
| `preflight.py` | 校验环境、网卡和端口占用 | 首次启动前 | `python3 scripts/preflight.py` | 只检查，短暂bind测试端口，不改网络规则 |
| `install.sh` | 首次安装编排 | 新环境首次启动 | `bash scripts/install.sh --start` | 加载镜像、创建卷/服务，空MySQL卷自动执行SQL |
| `prepare_database.py` | 从审阅模板生成带目标调度令牌的SQL并设置mysql用户权限 | 加载MySQL镜像后，首次启动MySQL前 | `python3 scripts/prepare_database.py` | 写runtime SQL，不连接数据库 |
| `init_minio.py` | 等待MinIO并创建9个默认私有空桶 | 初装自动调用；已有MinIO补桶 | `python3 scripts/init_minio.py` | 创建缺失桶；不删除对象、不改已有桶策略 |
| `doctor.sh` | 进程/HTTP/数据库基础诊断 | 安装后/排障 | `bash scripts/doctor.sh` | 只读查询，不修复配置 |
| `build-voice-images.sh` | 从已固定基础镜像构建语音发行镜像 | 维护/重新构建镜像，普通离线安装不需要 | `bash scripts/build-voice-images.sh` | 构建镜像标签，不重启容器 |
| `freeswitch-entrypoint.sh` | 容器启动时复制已生成配置并启动FreeSWITCH | Compose自动调用 | 不在宿主机手工执行 | 替换新容器内部配置目录，启动进程 |
| `unimrcp-entrypoint.sh` | 容器启动时复制配置/补种子文件并启动UniMRCP | Compose自动调用 | 不在宿主机手工执行 | 替换容器内部配置并启动进程 |

## prepare_install.py

首次运行：`python3 -B scripts/prepare_install.py`。需可用Docker守护进程、OpenSSL和交互式SSH终端；推荐root。无需额外Python包，兼容Python3.6。不会安装Docker或访问外部IP探测网站。

默认拒绝已有`.env`。只有尚无该项目容器/数据卷、需要调整首次生成配置时，显式执行`python3 -B scripts/prepare_install.py --edit-before-install`。支持内网/云公网、项目名、IP、SIP域、Web端口、MySQL库名/业务账户、MinIO管理账户、4项基础密码、ESL密码、私网管理访问。密码隐藏输入、二次确认，支持回车保留随机/已有值；生成摘要不显示密码。使用现有env时备份并保留内部密钥，不偷偷轮换。

保存前再次检查项目是否空，拒绝已有卷/容器，检查绑定IP必须在本机；输入YES才写入。脚本可能在保存env后render失败，此时按报错修正并运行render，不能重新init。它不是已部署环境的密码轮换或IP迁移工具。

## configure.py

```bash
python3 scripts/configure.py --help
python3 scripts/configure.py init --bind-ip 内网IP
python3 scripts/configure.py init --bind-ip 私网IP --public-ip 公网IP
python3 scripts/configure.py render
python3 scripts/configure.py comment-env
```

- `init`：只用于没有env的新环境，生成随机服务密码、内部密钥和两组配套RSA；保留中文注释，拒绝覆盖已有env。不会导入SQL、启动服务或注册后台节点。
- `render`：验证env，生成/解析FreeSWITCH、UniMRCP、Nginx配置，把前端两处RSA占位符替换为当前env值并生成gzip。不会自动复制到已启动的FreeSWITCH容器内，也不刷新容器环境，所以改ESL密码后还须重建容器。不要用原始dist绕过render直接替换运行目录。
- `comment-env`：给旧env补中文注释，私有备份到runtime/backups，逐项验证原值和扩展键不变；不生成新密钥、不启动服务。

不要执行`source .env`，文件使用严格KEY=VALUE而非Shell脚本语法。不要把整份env上传聊天或提交Git。

## preflight.py

需要Linux root和`.env`。检查绑定IP是否真实存在；检查本包固定端口与电话/MRCP RTP范围的占用。逐个测试TCP/UDP bind，已启动的本项目也会造成“端口占用”，因此不能把它当运行中健康检查。

不检查云控制台安全组、路由完整性、DNS或证书，不自动释放端口，也不证明SIP/RTP双向网络已通。

## install.sh

```bash
bash scripts/install.sh
bash scripts/install.sh --start
```

不带参数：render、Compose解析、发行SHA256校验，不加载镜像、不启动服务。带`--start`：要求Linux x86_64、root和已准备env，拒绝项目正在运行；预检端口、加载11个离线镜像，读取镜像内mysql UID/GID后准备SQL，再启动基础服务、建MinIO桶、启动其余服务。小于约8GB仅告警。

不是升级入口；不能因失败就删除卷重跑。启动模式会再次拒绝本项目已有容器或命名卷；其他项目/非Docker进程的端口占用由preflight检查。首次运行前仍须核查主机旧数据，不能绕过保护复用旧数据库。发行文件被自行修改后SHA校验会报错，不能通过删除清单掩盖未知改动。

## prepare_database.py

```bash
python3 scripts/prepare_database.py --check-only
python3 scripts/prepare_database.py
```

`--check-only`：校验review.json、模板/占位SQL哈希及调度令牌格式，不生成文件、不连接数据库。生成模式要求Linux root、已加载本包MySQL镜像，通过两个无网络临时容器读mysql UID/GID；输出runtime/database/init下两个文件，目录700、SQL400并归mysql用户所有，解决entrypoint降权后无法读取的问题。

不会直接执行SQL。MySQL空卷启动才执行挂载目录脚本；已有库重启不会补跑。V160已在主脚本内，第二脚本仅注释占位，避免重复DROP INDEX。原始SQL不分发；自定义SQL须重新审阅，不能直接套用旧模板。

## init_minio.py

```bash
python3 scripts/init_minio.py
python3 scripts/init_minio.py --wait-seconds 120
```

读取env的MINIO_INTERNAL_ENDPOINT（默认127.0.0.1:9000）、管理账号/密码、区域。用Python标准库做S3签名，无需curl/mc/额外镜像。默认等待60秒，允许0至600秒；创建minio/buckets.txt中的9个桶。可重复执行，已有对象和策略不动；错误凭据会失败，不当作桶已存在。不会配置后台OSS或迁移旧桶文件。

## doctor.sh

显示Compose状态，检测Java的127.0.0.1:8080和MinIO的9000存活接口，再用容器内MySQL业务账户查询表数量。宿主机需curl。失败返回非零；表数量仅用于观察，不验证每张表和业务记录，不判断所有调度任务是否执行，不代替登录/通话/录音验收。

## build-voice-images.sh

开发维护人员使用。校验基础镜像ID及Linux x86_64 Docker环境，使用build/上下文、`--pull=false --network=none`构建本包两个独立语音镜像。基础镜像缺失或ID不符会拒绝；不是容器快照，不拷贝运行数据，不自动更新已启动容器。普通安装已带构建后的离线镜像，无需运行。

## 两个 entrypoint

由Compose自动执行。freeswitch-entrypoint把`/etc/freeswitch-template`中的已渲染配置复制到容器内部`/etc/freeswitch`，然后启动FreeSWITCH，避免沿用镜像示例用户/网关；unimrcp-entrypoint把`/release-conf`复制到容器内部conf目录，必要时补data种子，再执行服务命令。它们有固定目录清理动作，**不要在宿主机手工运行，也不要改挂载把目标目录指向主机重要配置**。

## 推荐调用顺序

首次：校验发行文件 → prepare_install → install（检查）→ install --start → doctor → 后台节点/OSS配置 → 真实业务验收。

修改ESL/XML：备份env → 手工修改 → render → 无通话时重建freeswitch → 后台密码同步 → fs_cli验证。

升级：按OPERATIONS.md备份、替换匹配JAR与前端、render并重建server/nginx；不运行prepare_install、init、install --start，不删除卷。
