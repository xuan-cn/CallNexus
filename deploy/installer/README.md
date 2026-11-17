# CallNexus 一次性初始化容器

本工具为现有 R3 Docker Compose 安装包的补充，不替代完整安装包，不含业务镜像、数据库或真实凭据。
本次不实现商业授权、部署身份、公私钥绑定或签发工具。
宿主机只需要 Linux、Docker、Compose 和 Shell；Python、OpenSSL、CA 证书、脚本均在初始化镜像中。

## 目录与安全边界

- 本工具目录：Dockerfile、installer.py、legacy、compose.installer.yaml、installer.sh。
- 挂载目标：完整部署目录 → 容器 `/workspace`。`.env`、runtime 配置和 SQL 输出持久化到宿主机。
- 只执行镜像中内置的 Python 脚本，不执行挂载目录中同名脚本。
- 不挂 Docker Socket、不使用 privileged、不启动/停止业务容器、不自动导入或清理数据库。
- 容器默认执行后删除，无端口映射、无常驻进程；工具 Compose 与业务 Compose 分开，且有 tools profile。
- 使用 host 网络检查本机地址和端口，也用于显式 MinIO 初始化；镜像自身不提供网络监听服务。
- 初始写文件使用 root（用于匹配生成文件权限）；仅启用 CHOWN/FOWNER/DAC_OVERRIDE 能力和 no-new-privileges。
- Shell 入口采集本机 Docker 的项目与卷清单，SQL 准备时从已加载的 MySQL 镜像读取 UID/GID，不硬编码。
- 首次准备缺少宿主机检查结果时拒绝执行。不要绕过 installer.sh，也不要并行启动业务服务。
- `.installer-lock` 防止多个初始化工具并行操作。异常断电留下锁时，确认没有初始化任务后再人工清理锁目录。
- CentOS 启用 SELinux 时需要检查共享目录标签策略；不要为排障直接关闭 SELinux 或把配置目录改成 777。

## 制作与离线交付

选择 B（192.168.0.144）的独立工具目录，不在 A（192.168.0.176）的生产目录构建或替换。
源代码上传解压后进入本工具目录执行：

```bash
sh build-image.sh
```

制作镜像阶段需要能够获取 python:3.12-slim-bookworm 基础镜像和 Debian 软件源。
生产发行时应记录并固定已验证基础镜像摘要；本版本在尚未构建时不能冒充已固定镜像产物。
脚本构建后只执行容器 help 自检，然后导出 images/callnexus-installer-20261106.tar 和 SHA256SUMS。
客户离线部署机不需要构建、联网或安装 Python：

```bash
sha256sum -c images/SHA256SUMS
docker load -i images/callnexus-installer-20261106.tar
```

### 构建机无法访问 Docker Hub

首选在另一台可以访问 Docker Hub 和 Debian 软件源的 Linux x86_64 Docker 主机构建完整最终镜像，
将脚本输出的整个 images 目录传到 B，校验后 docker load。B 不再执行 build-image.sh。
不能只导入原版 Python slim 就宣称可以离线构建，因为普通 Dockerfile 的 apt-get 也需要网络。

如果已有经审核、含 Python/OpenSSL CLI/CA 的本地基础镜像，可用更新工具包执行：

```bash
CALLNEXUS_INSTALLER_OFFLINE_BUILD=1 \
CALLNEXUS_INSTALLER_BASE_IMAGE=实际已导入的基础镜像标签 \
sh build-image.sh
```

Dockerfile.offline 不执行 apt-get，只校验基础依赖。不要使用来历不明镜像或关闭 TLS 校验。
普通联网构建可通过 CALLNEXUS_INSTALLER_PYTHON_IMAGE 指定企业批准的 Python 镜像来源；软件源仍须可用。

保留现有业务 Compose 和数据卷；工具 Compose 的 `pull_policy: never` 不会在线自动拉取初始化镜像。

## 首次安装

先将完整安装包解压到独立的新目录，例如 `/opt/CALLNEXUS-installer-test/callnexus-deploy`，不要覆盖 B 的现有部署。
加载全部业务镜像后，工具目录中执行：

```bash
sh installer.sh /opt/CALLNEXUS-installer-test/callnexus-deploy prepare
```

交互设置内网/云部署、本机与公网 IP、端口、MySQL/Redis/MinIO 密码和 ESL 密码。
测试项目名请使用新的 `callnexus_installer_test`，不要使用已有生产/测试项目名。
密码隐藏输入、二次确认，生成带中文注释的 `.env`。已有 `.env` 默认拒绝首次准备，不自动换密钥。
镜像内部保留原 R3 密码字符限制，暂不新增密码语法。

检查和 SQL 准备：

```bash
sh installer.sh /opt/CALLNEXUS-installer-test/callnexus-deploy check
sh installer.sh /opt/CALLNEXUS-installer-test/callnexus-deploy sql-check
sh installer.sh /opt/CALLNEXUS-installer-test/callnexus-deploy sql-prepare
```

check 是首次安装端口预检，在业务已经启动时报告端口占用是正常的，不能据此重启或清理业务。
sql-check 校验审阅 SQL 的哈希与参数；sql-prepare 仅生成 runtime/database/init 并修正权限，不连接数据库。
已有同项目容器或卷时拒绝 sql-prepare。原始 database/init/001-schema.sql.sql 不改、不删。
自定义 SQL 必须先重新审阅，不能直接绕过校验；V160 已包含时不重复执行旧 DROP INDEX。
为客户预留的 database/init 目录仍位于原完整部署包中，本工具不内置或覆盖客户 SQL。
准备完成后，才由安装人员在宿主机运行原业务 `docker compose up -d`；本工具不自动启动服务。

## 已有系统更新

不要重新 prepare/init，不用本工具轮换已有数据库密码。
备份配置和运行目录、确保业务变更处于维护窗口后执行：

```bash
sh installer.sh /opt/CALLNEXUS/callnexus-deploy render
```

render 复用已有 `.env` 和密钥，重新生成配置，不更改数据库账号、不重建容器。
前端来源 frontend/dist 必须是部署占位符版；可用源码中的 npm run build:deploy 生成。
Nginx 实际使用 runtime/frontend，不是直接使用来源 frontend/dist。
新镜像内脚本忽略来源 .js.gz/.js.br，gzip 从已经替换密钥的 JS 重建，避免旧压缩副本覆盖。
render 不会自动删除 runtime/frontend 中更早版本遗留文件；版本更新前应备份并按完整前端更新流程清理旧版本。
env 变化不会自动生效，也不会改变已存在 MySQL/Redis/MinIO 的密码；维护人员另行按具体服务决定重建方式。

其他操作：

| 命令 | 作用 |
|---|---|
| help | 显示容器支持命令，无挂载也可执行 |
| init --bind-ip IP [--public-ip IP] | 非交互首次初始化，自动生成密码，不供已有安装使用 |
| comment-env | 备份并更新已有 .env 中文注释，保留全部配置值 |
| minio-init | MinIO 已启动后显式创建缺少的私有桶，不删除对象、不修改已有桶策略 |

## 验证状态与后续验收

本地执行 Python 单元测试验证脚本适配与文件生成，不代表真实 Linux Docker 验收。
当前本地没有 Docker，未生成可交付镜像 tar；工具 ZIP 只是构建补充包，不是完整离线安装包。
B 上需验证：镜像构建/help、空目录准备、重复执行拒绝、已有项目拒绝、私钥权限、SQL 镜像 UID/GID、
SELinux 目录读取、Compose 校验、实际登录（含 gzip）、MinIO 九个桶，以及完整业务回归。
以上通过后，再将镜像 tar 和本工具整合进下一版完整离线安装包。

## 来源

legacy 中的五个脚本来自本地 R3 包；configure.py 仅额外修复预压缩 JS 覆盖已渲染密钥的问题。
原始 ZIP：callnexus-deploy-20261028-final-r3.zip
SHA256：19fcac59332790b5c7187bd3ac6c4297b8b772472991cf38ea1655d044cfd9f2
没有复制原始数据库导出、真实 .env、日志或录音。
