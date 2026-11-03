# ASR 延迟检查（2026-11-02）

## 已确认

- 日志中的两段 8kHz/16bit/单声道 PCM：151200 字节为 9.45 秒，110400 字节为 6.9 秒，文本均仅 5 字符。
- 当前插件源码在每次 RECOGNIZE 后缓存所有 LISTENING 帧，包括 VAD ACTIVITY 前的等待段。原注释明确说明这是为了保留 VAD 约 300ms 检测延迟内的句首，不是仅凭音频长度推测。服务器 so 仍需核对是否对应此源码；无法从字节数证明音频含机器人声音。
- 两轮 ASR API 耗时 3276/4299ms，提供商调用分别 3269/4293ms；接口其他开销仅约 7/6ms。步骤理解分别 1150/1903ms，TTS 首包 273/410ms。已有数据不支持把问题归因于 SQL、ESL 或 TTS。
- UniMRCP 此入口是收齐整段 PCM 后调用 recording ASR，不是持续推流的实时识别。修改 streaming ASR 默认模型不一定改变这条链路。

## 本批诊断日志

- Java：节点鉴权 authMs、选择服务商/格式等 localPrepareMs、PCM 时长；百炼编码/请求准备 prepareMs、HTTP 往返 httpRoundTripMs、响应解析 parseMs。HTTP 往返包含 DNS/TLS/上传/服务商处理/下载，不能当作纯模型计算耗时。
- 插件：ASR onset 显示 VAD 触发时已有多少音频；ASR HTTP timing 显示音频时长与 curl DNS/建连/首字节/总耗时。这些 curl 时点是从请求开始累计，不可相加；首字节也包含后端处理，不是单纯网络延迟。
- 仅加诊断，不改变 VAD、截音、超时、模型选择及业务匹配。Java 更新 jar 后生效；插件日志需编译 callnexusrecog.so 并替换/重启 UniMRCP 后生效。本机未编译验证 so，不宣称已部署。

## 后续修复边界

不直接在 ACTIVITY 前清空缓存，否则会丢失句首。建议后续采用有限前滚缓存（需要覆盖 VAD 判定延迟和短句），ACTIVITY 后保留完整语音，静音后提交；用低音量、单字、快速回答、长停顿和多路并发验收后再部署。

常见确认可在“明确确认办理需求”步骤配置整句：是的、嗯是的、对、是。标点会标准化，但“嗯”不会自动删除。不得将这些词全局配置到身份、责任判断或拒绝规则中；不自动替用户修改数据库中的已发布流程。

## 现场只读核对

```bash
docker logs --since 30m unimrcp-server 2>&1 | grep -E 'RECOGNIZE sr=|ASR onset|Detected Voice|ASR POST|ASR HTTP timing|ASR OK|ASR dump'
docker logs --since 30m callnexus-server 2>&1 | grep -E 'AI ASR|百炼 ASR HTTP|百炼 ASR 结果解析|AI 实时 ASR 识别完成'
```

新标记在未更新版本上不存在。若需试听 dump，音频包含客户声音，应限制访问、使用测试号码并在检查后按要求清理，不默认开启生产音频转储。
