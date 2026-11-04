# 线路管理运行状态

## 使用

线路列表保留“配置状态”，增加“运行状态”。进入列表、搜索、翻页、保存后自动读取当前页状态，也可点击“刷新状态”。鼠标悬停查看 FreeSWITCH 原始注册/探测状态、重试信息及查询时间。

这是查询时的快照，不是持续推送。注册成功或 SIP 探测正常不保证号码路由、余额、RTP 音频及实际通话成功。

| 接入方式 | 判断方式 |
| --- | --- |
| 主动注册 | REGED 显示已注册；TRYING/REGISTER 显示注册中；FAILED/FAIL_WAIT/EXPIRED 显示注册失败；其他未注册状态显示未注册 |
| IP 互信 | NOREG 为正常免注册模式；运行时 pingfreq > 0 且 UP 才显示探测正常；DOWN 显示探测异常；pingfreq 为 0 显示免注册·未探测 |
| 设备注册 | 在配置的 SIP Profile 注册表中匹配节点 SIP 域与 registeredIdentity，并排除已到期记录；不存在有效记录显示未注册 |
| 所有方式 | 配置停用时不查询；ESL 不通或认证失败显示节点不可达；状态格式异常显示查询失败；未找到网关显示网关未加载 |

对主动注册线路，已注册但启用了探测且 DOWN 时，优先显示探测异常。不根据数据库的启用标记推断真实在线状态。

## 接口和权限

- `GET /api/v1/freeswitch-gateways/runtime-status?ids=1,2`
- 沿用 `callcenter:freeswitch-gateway:list`，不额外要求节点管理或监控菜单权限。
- 每次最多 100 个 ID，只加载当前租户未删除的线路；节点信息由后端线路配置解析，前端不能指定任意节点或执行命令。
- 不返回密码、ESL 地址或完整注册表，只返回请求线路的状态信息。
- 列表先展示配置，再独立读取状态。节点失败不阻塞列表查看/编辑；过期请求结果不会覆盖新页面。
- 同节点运营商网关只查一次 `api sofia xmlstatus gateway`；设备按节点/Profile 合并查询 `api sofia xmlstatus profile <profile> reg`。
- XML 解析禁用 DTD 和外部实体。Profile 参数使用白名单校验。

命令和 XML 字段依据 FreeSWITCH 1.10.12 官方源码：
https://github.com/signalwire/freeswitch/blob/v1.10.12/src/mod/endpoints/mod_sofia/mod_sofia.c

## 部署与验收

更新后端及前端即可，不涉及数据库迁移，不需要修改或重启 FreeSWITCH。

1. 使用仅有线路列表权限的普通账号检查状态查询。
2. 对照 `sofia status gateway 网关编码` 的 State/Status，检查主动注册成功、失败和 IP 免注册状态。
3. 对照 `sofia status profile internal reg`，测试设备注册与注销。
4. 对关闭探测的 IP 线路确认显示未探测，不能误判正常。
5. 在测试环境模拟节点不可达、返回无效内容，确认配置列表仍显示，且没有误报正常。
6. 快速翻页、搜索并刷新状态，确认旧请求不会覆盖当前页；停用线路不建立额外 ESL 查询。

实际服务器接入验收需部署后执行；本地单元测试仅覆盖解析、状态判定、分组、权限约束和失败隔离。
