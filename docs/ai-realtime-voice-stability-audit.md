# AI 实时电话语音链路稳定性审计与第二阶段改造

## 1. 审计范围

本次审计覆盖以下实现：

- `AiRealtimeMrcpEventService`：通话运行态、ASR、AI 回复、TTS 分段、播放完成和恢复。
- `FreeSwitchAiRealtimeMrcpGateway`：ESL 命令、通道变量和 FreeSWITCH 播放/识别控制。
- `FreeSwitchEslEventListenerManager`、`TelephonyEslEventHandler`、`TelephonyEventHandlerImpl`：ESL 订阅和事件分发。
- `AiRealtimeTtsStreamWebSocketHandler`、`AiRealtimeTtsInternalService`：内部 WS 和上游流式 TTS。
- `plugins/callnexus-synth/src/callnexus_synth_engine.c`：UniMRCP SPEAK/STOP、PCM 队列和 SPEAK-COMPLETE。

本轮只实施第一阶段审计和第二阶段播放完成异常修复。通话级 Actor、统一状态机和 C 端有界队列属于后续阶段。

## 2. 已确认调用链

1. `AiRealtimeMrcpEventService.tryRecognize` 通过 `FreeSwitchAiRealtimeMrcpGateway.recognize` 执行 Lua `detect_speech`。
2. UniMRCP ASR 完成后，FreeSWITCH 广播 `DETECTED_SPEECH`。
3. ESL Listener 解析事件，经 `TelephonyEslEventHandler`、`TelephonyEventHandlerImpl` 进入 `AiRealtimeMrcpEventService.handle`。
4. `processTurn` 或工作流处理识别文本，模型增量文本经 `SentenceSegmenter` 分句。
5. `dispatchNextSegment` 调用 `speak`，写入 `callId/turnId/segmentSeq/generation/turnEnd` 通道变量并执行 `uuid_broadcast ... speak`。
6. `callnexussynth` 从 MRCP Vendor 参数取得轮次字段，通过内部 WS 发送 `start/text/flush`。
7. Java 内部 WS 串行调用配置的 `StreamingTtsProvider`，将 PCM 二进制帧发送给插件。
8. 插件首个 PCM 入队后回发 `audio_ready`；Java记录 `TTS_FIRST_AUDIO`，但不将其视为播放完成。
9. Java 当前段全部 PCM 发送后回发 `segmentEnd/segment_completed`，并记录音频字节数。
10. C 插件在 `read_frame` 中按 FreeSWITCH 请求的帧大小消费 PCM；只有 `ws_done` 且 PCM 已排空才产生 MRCP `SPEAK-COMPLETE`。
11. FreeSWITCH 的 speak 应用结束并发出 `CHANNEL_EXECUTE_COMPLETE`；Java校验轮次身份后结束当前 Segment。
12. 最后一个 Segment 完成后结束 Turn，并启动下一轮 ASR。

## 3. 线程和资源归属

| 环节 | 执行线程 | 主要资源 | 当前释放方式 |
| --- | --- | --- | --- |
| ESL 读取 | 每节点 cached-pool listener | Socket、订阅 | 断线重连或服务停止关闭 |
| Java 通话处理 | ESL 线程、`aiRealtimeExecutor`、scheduler 混合 | `RuntimeSession`、定时器 | hangup/end 清理，多线程通过原子变量协调 |
| 模型流式回复 | `aiRealtimeExecutor` | Segment 队列、Turn | generation 隔离迟到回调 |
| 内部 TTS WS | Undertow IO + TTS 消费线程 | WS、段队列、TTS execution | close/cancel/idle timer |
| NLS TTS | NLS SDK 回调线程 | NlsClient、Synthesizer | completion/error/cancel 关闭 |
| C 合成插件 | 每 MRCP channel worker + media read | WS fd、PCM、turn context | STOP、Close、channel close |

当前并非严格的“每通电话单线程 Actor”。ESL、模型回调、WS 事件和定时器均可推进状态，这是第三阶段需要解决的结构性问题。

## 4. 已通过代码或日志确认的缺陷

### 4.1 播放完成事件缺失时会永久失去下一轮 ASR

旧实现的 `failTimedOutSpeak` 会：

- 清除活动播放；
- 设置 `recognizing=false`；
- 执行 `uuid_break`；
- 将整个实时会话设为 `FAILED`；
- 明确不启动下一轮识别。

日志已经出现 `phase=AUDIO_READY, timeoutMs=34695`，随后会话进入 FAILED。用户刚听到语音后再说话不会被识别，是该路径的确定结果。

### 4.2 `AUDIO_READY` 后仍使用文本估算总时长

旧实现收到首包后再次调用 `speakCompletionTimeout(text)`。该值只按字符估算，不知道实际 PCM 字节数，也不区分：

- 云端仍在持续合成；
- Java 已发送完音频；
- 插件仍在排空 PCM；
- FreeSWITCH 已完成播放但 ESL 事件未到达。

因此长句、慢语速和尾部网络抖动可能被旧定时器误杀。

### 4.3 播放完成关联依赖文本内容

旧实现主要通过 `Application-Data` 是否包含当前文本判断事件归属。文本转义、截断、迟到事件和同文本重复播报都会降低唯一性。

### 4.4 ESL 事件处理线程执行了较重的同步逻辑

Listener 在读取 Socket 后同步调用整个 `TelephonyEventHandlerImpl`。其中 AI 处理可能查询数据库、拉取通道变量并发起 ESL 命令。虽然 Socket 缓冲通常能暂存事件，但处理线程阻塞会增加完成事件延迟；ESL 断线时也没有历史事件重放。

### 4.5 首个完整事件可能只用于创建运行会话

`RuntimeSession.started` 首次从 false 变为 true 时会异步启动会话并直接返回。如果首个完整事件恰好是 `CHANNEL_EXECUTE_COMPLETE`，该完成事件不会继续进入 `onSpeakComplete`。常规呼叫通常先有其他事件，但代码上存在丢失首事件的路径。

## 5. 高概率风险，需要故障注入验证

- 多线程直接修改 `RuntimeSession`，原子变量只能保护局部字段，不能保证跨字段状态转换原子性。
- 内部 WS 使用有界 Segment 队列，但 C 端 PCM 使用 `realloc` 持续增长，没有总字节上限。
- C 端 `STOP` 响应依赖后续 `read_frame`，媒体线程停止调用时可能延迟释放。
- ESL 重连后会执行运行态对账，但无法补发丢失的 `CHANNEL_EXECUTE_COMPLETE`。
- `uuid_broadcast ... both` 可能产生多腿事件，需要通过客户腿 UUID 和轮次身份继续验证。
- 工作流播报超时不会调用 `voiceTtsCompleted` 假装正常完成；系统恢复收音并保留 `WAITING_TTS`，下一次用户输入通过现有 `INTERRUPTED` 路径恢复流程。后续仍应增加独立的 `voiceTtsAborted` 事件以增强可观测性。

## 6. 暂无证据的推测

- 不能根据当前日志认定阿里云 NLS 未发送完成事件。
- 不能根据 Java 未收到事件认定 FreeSWITCH 未产生事件；同一组日志已经显示 FreeSWITCH 侧存在正常 `SPEAK-COMPLETE`。
- “AI 意图话术向量已更新”不是音频格式或向量内容。FreeSWITCH 日志明确显示它作为普通 `Speaking text` 进入 TTS。源码中该字符串只存在于意图索引日志，现有证据不足以判断它来自数据库回复模板、工作流模板还是模型输出；需要同一 callId 的 Java“准备提交播报”日志确认来源。

## 7. 第二阶段已实施改造

### 7.1 阶段化看门狗

- `SUBMITTED`：等待首段音频，仍使用短超时发现无音频故障。
- `AUDIO_READY`：首包已进入插件，使用独立的音频发送阶段超时。
- `AUDIO_SENT`：全部 PCM 已发送，根据 `audioBytes / (sampleRate * 2)` 计算音频时长并增加裕量，等待 FreeSWITCH 播放完成。

配置项：

- `CALLNEXUS_AI_UNIMRCP_STREAMING_SPEAK_COMPLETE_TIMEOUT_MS`，默认 12000ms。
- `CALLNEXUS_AI_UNIMRCP_STREAMING_AUDIO_SEND_TIMEOUT_MS`，默认 120000ms。
- `CALLNEXUS_AI_UNIMRCP_PLAYBACK_COMPLETE_TIMEOUT_MS`，默认最短 30000ms，长音频自动延长。

### 7.2 完成事件身份校验

每次 SPEAK 下发并在 FreeSWITCH 通道变量携带：

- `callnexus_ai_turn_id`
- `callnexus_ai_segment_seq`
- `callnexus_ai_generation`

完成事件优先校验这些字段；字段缺失时才兼容旧版本的文本匹配。

### 7.3 超时恢复

看门狗命中后执行有界取消：

- 校验当前 `ActiveSpeak` 对象身份，旧定时器不能影响新 Segment；
- 递增 generation，隔离迟到的模型和音频回调；
- 取消当前 Turn 的内部 TTS WS；
- 清空未播放 Segment；
- 执行 `uuid_break` 停止残留播放；
- 当前 Turn 记录为 FAILED，但通话会话不进入 FAILED；
- 工作流保留 `WAITING_TTS` 并恢复收音，下一次用户输入按 `INTERRUPTED` 路径恢复；普通对话恢复 LISTENING 并启动 ASR。

该路径是“明确取消并恢复”，不是把未确认的播放伪装成正常完成。

## 8. 后续阶段

1. 引入每通电话串行事件执行器，所有 ESL、TTS、定时器和模型回调先转为会话事件。
2. 为工作流增加 `voiceTtsAborted(reason)`，替代超时时借用正常完成入口。
3. 增加 Application-UUID/MRCP request id 映射；底层无法提供时记录明确缺失。
4. C 插件改为有界分块 PCM 队列，禁止单个 `realloc` 无上限增长。
5. 增加 ESL 连接状态指标和重连后的播放状态协调。
6. 完成故障注入、20轮对话、多通并发和资源增长测试。

## 9. 回滚方式

第二阶段为 Java 侧兼容改造，不改变内部 WS 已有消息格式；只新增 Java 内部事件和 FreeSWITCH 通道变量。

回滚时恢复以下文件即可：

- `AiRealtimeMrcpEventService`
- `AiRealtimeTtsStreamWebSocketHandler`
- `AiRealtimeTelephonyGateway`
- `FreeSwitchAiRealtimeMrcpGateway`
- `AiKnowledgeProperties`
- `application.yml`

删除 `AiRealtimeTtsSegmentSentEvent`。无需回滚数据库，也无需替换 UniMRCP 插件。
