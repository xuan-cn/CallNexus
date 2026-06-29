<div align="center">

# CallNexus

[简体中文](#简体中文) | [English](#english)

</div>

---

<a id="简体中文"></a>

## 简体中文

### 项目简介

**CallNexus** 是一套基于 [RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus) 5.6.1 基座二次开发的、面向多租户场景的企业级后端服务。项目以 Spring Boot 3 + Java 17 为核心运行环境，遵循前后端分离、模块化分层架构，便于按业务域独立演进与按需装配。

- **配套前端仓库**：[`CallNexus-UI`](../CallNexus-UI)
- **配套设计 / 规范文档**：[`DevelopmentSpecifications`](../DevelopmentSpecifications)
- **当前后端版本**：`5.6.1`（继承自基座版本号）
- **构建工具**：Maven（多模块聚合工程，统一版本管理）
- **运行模式**：单体启动 + 模块化打包，支持 `local` / `dev` / `prod` 三套 Profile

> 本仓库仅包含后端工程；前端 UI 与系统级文档分别位于同级目录。

### 技术栈

#### 语言与运行时

| 类别 | 选型 |
| --- | --- |
| 编程语言 | Java 17 LTS |
| 构建工具 | Apache Maven |
| 容器化 | Docker（Sidecar Agent 部署） |

#### 后端框架

| 类别 | 选型 | 版本 |
| --- | --- | --- |
| 基础框架 | Spring Boot | 3.5.14 |
| 鉴权框架 | Sa-Token（含 JWT 整合） | 1.45.0 |
| ORM 框架 | MyBatis、MyBatis-Plus | 3.5.19 / 3.5.16 |
| 多数据源 | dynamic-datasource | 4.3.1 |
| 缓存 / 分布式锁 | Redisson、Lock4j | 3.52.0 / 2.2.7 |
| 数据库迁移 | Flyway | — |
| 任务调度 | SnailJob | 1.10.0 |
| 工作流引擎 | Warm-Flow | 1.8.5 |
| 对象存储 | AWS SDK v2（S3 / MinIO 协议） | 2.28.22 |
| 短信服务 | sms4j | 3.3.5 |
| 第三方登录 | JustAuth | 1.16.7 |
| 对象映射 | MapStruct Plus | 1.5.0 |
| Excel 处理 | FastExcel | 1.3.0 |
| 工具库 | Hutool | 5.8.43 |
| 加密 | BouncyCastle | 1.83 |
| API 文档 | SpringDoc OpenAPI | 2.8.17 |
| 监控 | Spring Boot Admin | 3.5.8 |
| SQL 监控 | p6spy | 3.9.1 |
| JSON 处理 | Fastjson | 1.2.83 |
| IP 定位 | ip2region | 3.3.7 |

#### 中间件

| 类别 | 选型 |
| --- | --- |
| 关系型数据库 | MySQL 8.x |
| 缓存 | Redis |
| 对象存储 | MinIO（S3 兼容） |
| 软交换 | FreeSWITCH（ESL + `mod_xml_curl`） |

#### 前端技术栈（[`CallNexus-UI`](../CallNexus-UI)）

| 类别 | 选型 |
| --- | --- |
| 框架 | Vue 3 + TypeScript |
| 构建工具 | Vite |
| UI 组件库 | Element Plus |
| 流程设计 | LogicFlow |
| 音频可视化 | WaveSurfer.js |
| 原子化 CSS | UnoCSS |

### 工程结构

```text
CallNexus/
├── callnexus-admin                启动模块，负责装配各业务模块
├── callnexus-common               公共基础能力（Web / Redis / OSS / Sa-Token / 日志 / 加密 等）
├── callnexus-extend               扩展能力（监控、SMS、第三方等）
├── callnexus-modules/             业务模块聚合目录
│   ├── callnexus-system           系统管理（基座保留）
│   ├── callnexus-resource         资源域服务
│   ├── callnexus-agent            坐席域服务
│   ├── callnexus-esl              ESL 网关与事件分发
│   ├── callnexus-call             通话相关服务
│   ├── callnexus-ivr              IVR 流程服务
│   ├── callnexus-customer         客户与工单服务
│   ├── callnexus-outbound         外呼任务服务
│   ├── callnexus-ai               AI 能力扩展
│   └── callnexus-workflow / job / generator / demo  基座沿用模块
├── script                         数据库及部署脚本
├── image                          系统截图
├── logs                           运行日志
└── pom.xml                        Maven 父级聚合 POM
```

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.x、Redis 6+、MinIO（或任意 S3 兼容存储）
- 如需软交换能力：FreeSWITCH 1.10+（开启 `mod_xml_curl`、`mod_event_socket`）

### 快速开始

```bash
# 1. 克隆仓库
git clone https://github.com/<your-org>/CallNexus.git
cd CallNexus

# 2. 构建
mvn clean install -DskipTests

# 3. 启动（默认 dev profile）
cd callnexus-admin
mvn spring-boot:run
```

### 许可证

本项目继承 RuoYi-Vue-Plus 基座的开源许可，详见 [`LICENSE`](./LICENSE)。

---

<a id="english"></a>

## English

### Introduction

**CallNexus** is an enterprise-grade, multi-tenant backend service built on top of [RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus) 5.6.1. It runs on Spring Boot 3 + Java 17, adopts a front-end / back-end separated and modular architecture, and supports independent evolution and on-demand assembly of each business domain.

- **Companion frontend repository**: [`CallNexus-UI`](../CallNexus-UI)
- **Companion design / specification documents**: [`DevelopmentSpecifications`](../DevelopmentSpecifications)
- **Current backend version**: `5.6.1` (inherited from the base framework)
- **Build tool**: Maven (multi-module aggregate project with unified version management)
- **Runtime model**: Single-process startup with modular packaging; supports `local` / `dev` / `prod` profiles

> This repository contains the backend project only. The frontend UI and system-level documentation are located in sibling directories.

### Technology Stack

#### Language & Runtime

| Category | Choice |
| --- | --- |
| Programming Language | Java 17 LTS |
| Build Tool | Apache Maven |
| Containerization | Docker (used for the sidecar agent) |

#### Backend Frameworks

| Category | Choice | Version |
| --- | --- | --- |
| Core Framework | Spring Boot | 3.5.14 |
| Authentication | Sa-Token (with JWT) | 1.45.0 |
| ORM | MyBatis, MyBatis-Plus | 3.5.19 / 3.5.16 |
| Multi-DataSource | dynamic-datasource | 4.3.1 |
| Cache / Distributed Lock | Redisson, Lock4j | 3.52.0 / 2.2.7 |
| Database Migration | Flyway | — |
| Job Scheduling | SnailJob | 1.10.0 |
| Workflow Engine | Warm-Flow | 1.8.5 |
| Object Storage | AWS SDK v2 (S3 / MinIO compatible) | 2.28.22 |
| SMS | sms4j | 3.3.5 |
| 3rd-party Login | JustAuth | 1.16.7 |
| Object Mapping | MapStruct Plus | 1.5.0 |
| Excel Processing | FastExcel | 1.3.0 |
| Utilities | Hutool | 5.8.43 |
| Cryptography | BouncyCastle | 1.83 |
| API Docs | SpringDoc OpenAPI | 2.8.17 |
| Monitoring | Spring Boot Admin | 3.5.8 |
| SQL Monitoring | p6spy | 3.9.1 |
| JSON | Fastjson | 1.2.83 |
| IP Geolocation | ip2region | 3.3.7 |

#### Middleware

| Category | Choice |
| --- | --- |
| Relational Database | MySQL 8.x |
| Cache | Redis |
| Object Storage | MinIO (S3-compatible) |
| Softswitch | FreeSWITCH (ESL + `mod_xml_curl`) |

#### Frontend Stack ([`CallNexus-UI`](../CallNexus-UI))

| Category | Choice |
| --- | --- |
| Framework | Vue 3 + TypeScript |
| Build Tool | Vite |
| UI Library | Element Plus |
| Flow Designer | LogicFlow |
| Audio Visualization | WaveSurfer.js |
| Atomic CSS | UnoCSS |

### Project Structure

```text
CallNexus/
├── callnexus-admin                Bootstrap module that assembles all business modules
├── callnexus-common               Shared infrastructure (Web / Redis / OSS / Sa-Token / Logging / Crypto, etc.)
├── callnexus-extend               Extension capabilities (monitoring, SMS, 3rd-party integrations)
├── callnexus-modules/             Business modules
│   ├── callnexus-system           System management (inherited from base)
│   ├── callnexus-resource         Resource domain service
│   ├── callnexus-agent            Agent domain service
│   ├── callnexus-esl              ESL gateway and event dispatching
│   ├── callnexus-call             Call-related services
│   ├── callnexus-ivr              IVR flow service
│   ├── callnexus-customer         Customer & ticket service
│   ├── callnexus-outbound         Outbound campaign service
│   ├── callnexus-ai               AI extension service
│   └── callnexus-workflow / job / generator / demo  Inherited base modules
├── script                         Database & deployment scripts
├── image                          System screenshots
├── logs                           Runtime logs
└── pom.xml                        Maven parent aggregator POM
```

### Requirements

- JDK 17+
- Maven 3.8+
- MySQL 8.x, Redis 6+, MinIO (or any S3-compatible storage)
- Optional softswitch capability: FreeSWITCH 1.10+ (with `mod_xml_curl` and `mod_event_socket` enabled)

### Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/<your-org>/CallNexus.git
cd CallNexus

# 2. Build
mvn clean install -DskipTests

# 3. Run (uses the dev profile by default)
cd callnexus-admin
mvn spring-boot:run
```

### License

This project inherits the open-source license of the RuoYi-Vue-Plus base framework. See [`LICENSE`](./LICENSE) for details.
