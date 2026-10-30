# API 与事件契约

> 内存实验室已实现房间初始化与读取、控制命令、Match、事件与决策查询、房间 SSE、Agent 配置读取、草稿保存、Prompt 发布和席位观察。Prompt 版本列表、配置更新、历史对局列表和历史快照仍为规划。[openapi.yaml](openapi.yaml)是字段、必填、枚举与结构校验的契约，本文解释语义。状态机见[领域模型](03-domain-model.md)。

实验室模式使用内存数据和固定 Mock Agent。命令响应仅包含已保存的回执字段，当前房间状态另行查询。Match 不提供创建或结束时间；Decision 只提供执行状态、动作、错误和用量，不返回私有模型输入输出。重启后内存数据清空。

## 1. 三类接口

| 类别 | 入口 | 边界 |
| --- | --- | --- |
| 浏览器管理面 | `/api/v1` | 本机单操作者；可管理双方配置和查审计，不对外部 Agent 开放 |
| 内部 Java | Scheduler / Runtime / ActionSubmitter / Repository ports | 不经 HTTP，不把内部 Record 当公共 JSON；MVP 真正执行动作的入口 |
| 外部 Agent（未来） | `/agent/v1` + 远程 Agent 的 `/agent/v1/decide` | 标准观察和 Envelope；未来独立身份授权，不发送平台私有 Prompt/Key |

无手工替 Agent 落子的浏览器按钮；未来外部动作 HTTP 提交复用内部 ActionSubmitter。协议版本 `1.0`，URL 主版本用于 HTTP API，gameRulesVersion 固定在 Match，actionSchemaId 由对应游戏版本注册。Payload 不重复携带这些版本。破坏性字段变化发布新协议主版本；可选元数据增加按契约演进，不静默改变旧规则含义。

ID 为 UUID 字符串；版本/序号为非负安全整数，时间为 UTC；列索引 0..6。JSON 限制：Envelope 64 KiB、模型输入 32 KiB、Prompt 16 KiB；服务端拒绝重复 key、过深（>32 层）JSON 和非有限数，不进行代码块截取或自然语言修复。分页 limit 默认 50、最大 100；列表以服务端排序，游标是不透明字符串，不用客户端时间拼接。

## 2. 浏览器操作

| 方法与路径（省略 `/api/v1`） | 请求 / 返回 | 行为 |
| --- | --- | --- |
| GET /rooms/current | RoomView | 获取唯一房间与当前状态，不创建额外房间 |
| POST /rooms/current | RoomView，201 或 200 | 幂等获取或初始化唯一房间；不是多人建房 API |
| GET /rooms/{roomId} | RoomView | 当前 Match、执行状态、健康、revision、eventCursor |
| POST /rooms/{roomId}/commands | ControlCommand / CommandResult | start/pause/continue/step/set_mode/cancel_decision/reset |
| GET /agents/{agentId}/configuration | AgentView | 草稿、当前发布内容与模型配置（仅管理面） |
| PUT /agents/{agentId}/prompt-draft | DraftUpdate / PromptDraft | expectedDraftRevision 比较后保存；不发布 |
| POST /rooms/{roomId}/agents/{agentId}/prompt-versions | PublishPrompt / PromptVersion | 命令幂等，固定草稿版本后发布 |
| GET /agents/{agentId}/prompt-versions | PromptVersionPage | 版本历史，按 versionNo 降序 |
| PUT /rooms/{roomId}/agents/{agentId}/configuration | ConfigUpdate / AgentConfiguration | 新建不可变配置版本，下次 Decision 生效 |
| GET /rooms/{roomId}/matches | MatchPage | 本房间历史局，按 createdAt/id 降序 |
| GET /matches/{matchId} | MatchView | 当前或历史 Match 的管理面世界投影 |
| GET /matches/{matchId}/snapshots/{stateVersion} | StateSnapshot | 历史棋盘版本，不调用模型 |
| GET /matches/{matchId}/participants/{participantId}/observation | ObservationView | 按指定席位投影当前状态，不暴露内部快照 |
| GET /matches/{matchId}/events | GameEventPage | sequence > after，升序；仅客观游戏事件 |
| GET /matches/{matchId}/decisions | DecisionPage | 按 startedAt/id 升序；包含失败尝试 |
| GET /decisions/{decisionId} | DecisionView | 操作者审计详情；外部 Agent 不可使用 |
| GET /rooms/{roomId}/events | text/event-stream | 管理面持久化事件流，支持游标补发 |

所有读取在应用层校验 Room、Agent、Participant、Match 的关联。未来加账号时此校验不代替身份授权。GET room 返回同一只读事务的一致快照及 eventCursor；不是分别读取当前棋盘和较新游标。

控制与发布/配置命令包含 commandId、expectedRoomRevision、expectedMatchId。相同 ID 同内容返回原 HTTP 状态和 body，不受后续 revision 变化影响；不同内容 COMMAND_ID_CONFLICT。新 ID 旧修订返回 ROOM_REVISION_CONFLICT。即使忙碌拒绝也保存可识别命令回执，防止重试被延后执行。

草稿 PUT 使用独立 revision；网络响应丢失后先 GET 核对内容/revision，不把保存草稿当成可重放发布命令。发布仅接受 expectedDraftRevision，不允许正文另传一份与草稿不同的 Prompt。相同草稿内容的不同发布命令允许产生新版本。

## 3. 主要 JSON 示例

控制命令（200 只确认命令事务完成，不代表模型已经完成）：

```json
{
  "commandId": "10000000-0000-4000-8000-000000000001",
  "expectedRoomRevision": 12,
  "expectedMatchId": "20000000-0000-4000-8000-000000000001",
  "type": "step"
}
```

模式切换使用相同公共字段，type=`set_mode` 并携带 mode=`STEP`；其他命令不携带 mode。重置回执包含新 matchId，查询房间可获得当前状态；旧 expectedMatchId 作废。

草稿与发布：

```json
{"expectedDraftRevision": 2, "content": "优先阻止对手立即获胜，只返回要求的 JSON。"}
```

```json
{
  "commandId": "10000000-0000-4000-8000-000000000002",
  "expectedRoomRevision": 14,
  "expectedMatchId": "20000000-0000-4000-8000-000000000001",
  "expectedDraftRevision": 3
}
```

模型选择输出与实际提交（身份字段由 Runner 构造）：

```json
{"selectedActionId":"c3"}
```

```json
{
  "protocolVersion": "1.0",
  "matchId": "20000000-0000-4000-8000-000000000001",
  "participantId": "30000000-0000-4000-8000-000000000001",
  "turnId": "40000000-0000-4000-8000-000000000001",
  "decisionId": "50000000-0000-4000-8000-000000000001",
  "actionId": "60000000-0000-4000-8000-000000000001",
  "stateVersion": 0,
  "action": {"type":"place_disc","column":3}
}
```

```json
{
  "status": "EXECUTED",
  "actionId": "60000000-0000-4000-8000-000000000001",
  "decisionId": "50000000-0000-4000-8000-000000000001",
  "matchId": "20000000-0000-4000-8000-000000000001",
  "stateVersion": 1,
  "executedAction": {"type":"place_disc","column":3},
  "error": null
}
```

错误结构：

```json
{
  "code": "CONTROL_BUSY",
  "message": "当前决策尚未结束，未增加单步许可。",
  "retryable": false,
  "traceId": "70000000-0000-4000-8000-000000000001",
  "details": {"decisionId":"50000000-0000-4000-8000-000000000001"}
}
```

## 4. SSE 事件

帧示例（id 是当前 Room 内的 sequence，不是 Decision 或 stateVersion）：

```text
id: 24
event: ACTION_RESOLVED
data: {"sequence":24,"roomId":"00000000-0000-4000-8000-000000000031","matchId":"20000000-0000-4000-8000-000000000001","roomRevision":18,"type":"ACTION_RESOLVED","occurredAt":"2026-10-19T08:00:00Z","payload":{"stateVersion":1,"decisionId":"50000000-0000-4000-8000-000000000001"}}

```

| type | payload | 前端处理 |
| --- | --- | --- |
| ROOM_INITIALIZED / MATCH_RESET | currentMatchId | 重新 GET RoomView，清除旧局实时缓存 |
| EXECUTION_CHANGED | executionMode、executionStatus | 更新状态或刷新 RoomView |
| PROMPT_PUBLISHED | agentId、promptVersionId、versionNo | 刷新服务端版本，不覆盖本地草稿 |
| CONFIGURATION_PUBLISHED | agentId、configVersionId、versionNo | 刷新配置 |
| DECISION_STARTED | decisionId、participantId、promptVersionId | 刷新状态和决策摘要 |
| DECISION_FINISHED | decisionId、status、errorCode（可空） | 刷新详情，显示失败或取消 |
| ACTION_RESOLVED | decisionId、stateVersion | 刷新棋盘；可将相邻失效合并为一次 GET |
| MATCH_FINISHED | result | 刷新终局状态 |
| SERVER_RECOVERED / ROOM_UNAVAILABLE | reason | 显示恢复暂停/故障，刷新权威状态 |

MVP 的 SSE 是失效通知加小型状态字段，不承诺每条事件携带完整 RoomView；客户端以查询返回的 revision 为准，旧请求不能覆盖较新状态。所有有限事件类型用 OpenAPI 枚举，payload 的语义以本表为准。GameEvent 单独提供 MatchInitialized、DiscPlaced、MatchFinished 等可重放事实。

首次 after 默认 0；有 Last-Event-ID 时优先使用。游标负数/非整数为 400，超前于当前持久化序号为 409 CURSOR_INVALID。正常数据不清理，故不存在正常保留窗口过期；数据库恢复/更换造成游标失效则重新 GET 并新建 EventSource。慢客户端断开后按已处理游标补发；心跳不带 id。浏览器刷新先 GET 再订阅，草稿保存状态独立处理。

## 5. 错误与重试

| HTTP | code | 自动处理 |
| --- | --- | --- |
| 400 | INVALID_JSON / INVALID_REQUEST / UNSUPPORTED_PROTOCOL | 修正请求；无可信 ID 不留动作回执 |
| 404 | ROOM_NOT_FOUND / MATCH_NOT_FOUND / AGENT_NOT_FOUND / DECISION_NOT_FOUND | 检查目标，不重试生成 |
| 409 | ROOM_REVISION_CONFLICT / COMMAND_ID_CONFLICT / CONTROL_BUSY | 刷新后由新用户动作创建新命令 |
| 409 | ACTION_ID_CONFLICT / DECISION_ALREADY_RESOLVED / DECISION_CANCELLED / DECISION_EXPIRED / STALE_MATCH / STALE_STATE / TURN_MISMATCH | 不改变终态，不重新选择并伪装网络重试 |
| 409 | DRAFT_REVISION_CONFLICT / MATCH_NOT_ACTIVE / NO_ACTIVE_DECISION / CURSOR_INVALID | 获取最新状态 |
| 422 | SCHEMA_VALIDATION_FAILED / ILLEGAL_ACTION / CONTEXT_TOO_LARGE / MODEL_CONFIGURATION_INVALID / MEMORY_MODE_NOT_SUPPORTED | 调整请求；发生于当前决策时记录失败并暂停 |
| 429 | RATE_LIMITED | 保留原命令/动作 ID，遵从 Retry-After；不自动调用模型 |
| 500 | INTERNAL_ERROR | retryable=false，不自动重试；控制命令或动作提交结果不明时先查询状态，必要时复用原 ID；不生成新动作 |
| 503 | STORAGE_UNAVAILABLE / ROOM_UNAVAILABLE | 对未知提交结果先查询或复用原 ID；不发新动作 |

MODEL_TIMEOUT、MODEL_UNAVAILABLE、MODEL_RATE_LIMITED、MODEL_REFUSED、MODEL_OUTPUT_EMPTY、MODEL_OUTPUT_TRUNCATED、MODEL_OUTPUT_INVALID 是异步 DecisionRecord 的错误码，控制命令已返回 200 后不能再冒充其 HTTP 失败。retryable 指原传输请求可重试，不表示允许重新生成动作。模型错误均无自动重发，人工继续后为新 Decision。

未来动作提交的可识别领域拒绝返回 ActionSubmissionResult(status=REJECTED,error=上述结构)，HTTP 409/422；格式/身份/ID 内容冲突返回 Error。OpenAPI 对相关错误定义两者联合。内部调用同样区分提交回执与传输故障。

## 6. 安全与未来外部协议

MVP 本机管理面：仅 loopback、同源开发代理、JSON 内容类型、验证 Origin（允许应用同源或非浏览器无 Origin 请求），不配置通配 CORS。变更请求要求 `X-Frayground-Client: laboratory` 防止简单跨站表单触发；它不是认证凭证。部署到网络前必须新增真实授权及相应 CSRF 方案，不靠 UUID 的难猜性。

管理面每 IP 变更 10 次/秒（突发 20）、查询 50 次/秒（突发 100）、SSE 最多 4 条；内存 token bucket 足以满足单实例，超限 429/Retry-After。内部 Runner 不经浏览器限流器。代理部署后的可信 IP 处理留待正式部署设计。

未来 Pull：Agent 凭身份领取自己的 DecisionRequest，固定期限内提交 Envelope，再查 actionId 结果；领取重试返回同一未完成任务，不能创建额外 Decision。未来 Push：平台发送相同 DecisionRequest 到 Agent 的 `/agent/v1/decide`，Agent 确认接受后通过平台 `/agent/v1/actions` 提交；相同 decisionId 的投递重试不能启动第二次工作。正式租约、鉴权和 SSRF 防护在实现远程接入前另行决定，当前不开放任意 URL。

DecisionRequest 只包含合法观察、规则说明、动作 Schema/候选、期限和可选非敏感 agentVersion，不要求第三方提供 Prompt 或模型 Key。身份授权、CSRF、防重放、幂等、状态版本、限流、Schema、规则校验各负其责；Decision ID/可选 Turn Token 不能代替身份授权。
