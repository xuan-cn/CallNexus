# FreeSWITCH 媒体同步 Agent 部署

## 用途

媒体 Agent 负责从 CallNexus 领取声音同步任务，将 MinIO 中的源音频下载并通过 FFmpeg 转换为 FreeSWITCH 标准 WAV，然后写入与 FreeSWITCH 容器共享的声音目录。

Agent 不需要 MinIO 密钥、数据库密码、ESL 密码或 SSH 权限。

## 构建镜像

```bash
cd /development/callhub/deploy/freeswitch/media-agent
docker build -t callnexus-media-agent:1.0.0 .
```

## 准备节点

1. 在 CallNexus 的 FreeSWITCH 节点页面启用媒体 Agent。
2. 将媒体根目录设置为 `/var/lib/freeswitch/sounds/callnexus`。
3. 点击“生成/重置 Agent Token”，立即保存页面仅展示一次的 Token。
4. 将节点加入需要发布媒体的节点组。

## Docker Compose 示例

```yaml
services:
  freeswitch:
    volumes:
      - callnexus-sounds:/var/lib/freeswitch/sounds/callnexus

  callnexus-media-agent:
    image: callnexus-media-agent:1.0.0
    restart: unless-stopped
    environment:
      CALLNEXUS_API_BASE_URL: http://192.168.1.121:8080
      CALLNEXUS_NODE_CODE: FS_LOCAL_01
      CALLNEXUS_NODE_TOKEN: 替换为节点独立Token
      CALLNEXUS_MEDIA_ROOT: /var/lib/freeswitch/sounds/callnexus
      CALLNEXUS_POLL_SECONDS: 10
    volumes:
      - callnexus-sounds:/var/lib/freeswitch/sounds/callnexus

volumes:
  callnexus-sounds:
```

## 验证

发布一条 IVR 提示音后：

```bash
docker logs -f callnexus-media-agent
docker exec freeswitch find /var/lib/freeswitch/sounds/callnexus -type f
docker exec freeswitch fs_cli -x "originate user/1001 '&playback(/var/lib/freeswitch/sounds/callnexus/000000/ivr-prompt/媒体ID/版本号/audio.wav)'"
```

同步失败时先查看声音媒体页面的节点同步详情，再检查 Agent 容器日志和共享卷挂载。
