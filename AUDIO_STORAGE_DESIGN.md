# CallNexus 声音文件存储设计

## 设计目标

FreeSWITCH 相关声音文件必须按业务分类写入不同的 OSS 配置和 MinIO 桶，禁止直接使用系统默认 OSS 配置。这样可以分别设置访问权限、生命周期、备份和清理策略。

## 分类与配置键

| 声音分类 | OSS 配置键 | 推荐 MinIO 桶 | 建议权限与生命周期 |
| --- | --- | --- | --- |
| 通话录音 | `call-recording` | `callnexus-call-recordings` | 私有；按合规要求设置保留期限 |
| 振铃音 | `ringback-tone` | `callnexus-ringback-tones` | 私有；长期保留 |
| 队列等待音乐 | `queue-wait-music` | `callnexus-queue-wait-music` | 私有；长期保留 |
| IVR/系统提示音 | `ivr-prompt` | `callnexus-ivr-prompts` | 私有；长期保留并支持版本管理 |
| 用户上传音乐 | `user-music` | `callnexus-user-music` | 私有；按租户配额管理 |

配置键可通过环境变量覆盖：

```text
CALLNEXUS_AUDIO_CALL_RECORDING_CONFIG_KEY
CALLNEXUS_AUDIO_RINGBACK_TONE_CONFIG_KEY
CALLNEXUS_AUDIO_QUEUE_WAIT_MUSIC_CONFIG_KEY
CALLNEXUS_AUDIO_IVR_PROMPT_CONFIG_KEY
CALLNEXUS_AUDIO_USER_MUSIC_CONFIG_KEY
```

## 开发约束

1. `sys_oss` 继续保存文件物理对象元数据，`cc_media_asset` 保存稳定媒体 ID、声音分类、用途、音频参数和 AI 派生信息。
2. 声音文件上传必须调用 `ISysOssService.upload(file, configKey)`，禁止调用默认上传方法。
3. 业务代码通过 `MediaAssetCategory` 和 `MediaStorageProperties` 获取配置键，禁止硬编码桶名称。
4. 每个租户可以创建同名 `configKey` 的 OSS 配置，框架会按“租户 + configKey”隔离客户端。
5. MinIO 桶由 OSS 配置管理。部署前必须在文件管理中创建并启用对应配置，不能依赖业务上传时临时切换默认桶。
6. FreeSWITCH 播放声音时不能长期依赖短时签名 URL。后续媒体管理模块应负责将已启用的提示音或音乐同步到 FreeSWITCH 本地媒体目录，或提供稳定的内部媒体下载接口。
7. 已有录音继续按 `sys_oss.service` 定位实际 OSS 配置并生成预签名 URL；开发环境可直接重新上传到分类桶。

## 前端访问规则

- 业务接口禁止直接把 MinIO 私有桶的永久对象地址返回给前端。
- 单文件访问统一调用 `OssService.selectUrlById(ossId, ttl)`。私有桶返回指定有效期的预签名 URL，公有桶返回原始 URL。
- 多文件或通用文件组件继续调用 `OssService.selectUrlByIds`、`OssService.selectByIds`，系统层会为私有桶生成默认 120 秒预签名 URL。
- 业务模块只决定签名有效期，例如录音在线播放使用 2 小时；签名实现、桶权限判断和异常降级由系统 OSS 服务统一负责。

## AI 扩展规则

- `cc_media_asset` 已预留 `transcript_status`、`transcript_text`、`transcript_oss_id`、`summary_text`、`keywords_json`、`sentiment_json` 和 `ai_metadata_json`。
- 带时间戳、说话人和置信度的完整转写结果保存为 JSON 文件，并通过 `transcript_oss_id` 关联；普通查询使用 `transcript_text`。
- TTS 或 AI 生成语音使用 `source_type`、`voice_provider`、`voice_model`、`voice_name` 和 `source_text` 记录生成来源。
- AI 处理必须生成派生结果，不得覆盖原始声音文件。
- 后续 AI 任务通过稳定 `mediaId` 请求处理，禁止把预签名 URL 保存为任务业务标识。

## 当前进度

- 通话录音已使用 `call-recording` 配置键上传。
- 已建立统一 `cc_media_asset` 媒体资产表，通话录音同时关联 `recording_media_id`。
- 已实现振铃音、等待音乐、IVR 提示音和用户音乐的分类上传、列表、试听、启停和删除保护。
- 声音媒体管理列表不展示通话录音；通话录音仅通过通话记录、录音管理和后续质检模块访问。
- 已建立媒体不可变版本、按通用 FreeSWITCH 节点组发布、节点同步任务和 Sidecar Agent 拉取机制。
- 节点 Agent 将源音频统一转换为 WAV PCM、8kHz、16bit、单声道，并写入共享声音目录。
- 媒体允许部分发布；后续 IVR 发布时必须逐节点校验引用媒体版本是否可用。
- 已预留转写状态、转写文本、摘要、关键词、情绪、AI 元数据和 TTS 来源信息。
- 后续需增加 AI 异步任务、媒体清理策略和真实业务引用计数维护。
