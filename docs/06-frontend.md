# 实验室前端设计

> 待实现；React 19.3 / TypeScript 6 / Vite 8 / Radix Themes 3 / CSS Modules。接口以[OpenAPI](openapi.yaml)为准，不将数据库 Entity 当组件状态。

## 1. 页面与组件

桌面三栏：左 Agent A（约 300px）、中间棋盘与控制（可伸缩）、右 Agent B（约 300px）；下方事件时间线与决策详情。窄屏按 Agent A、棋盘、Agent B 堆叠，控制栏保持可读；不额外实现移动原生客户端。

```text
LaboratoryPage
├─ ConnectionBanner / RecoveryBanner
├─ AgentPanel(A)                 AgentPanel(B)
│  ├─ PromptEditor / SaveDraft / PublishPrompt
│  ├─ RuntimeAndModelSettings
│  ├─ PublishedVersion / DecisionStatus
│  └─ LastDecisionSummary / OutputDetails
├─ MatchStage
│  ├─ MatchControls / ExecutionMode
│  ├─ GameViewRegistry → ConnectFourBoard
│  └─ ResultBanner
└─ HistoryPanel
   ├─ MatchSelector / ReplayControls
   ├─ GameTimeline
   └─ DecisionInspector
```

Radix 用于按钮、表单、Tabs、Dialog 等通用交互，棋盘用 CSS Grid，Prompt 用普通 textarea，首版不引入代码编辑器。控件显示产品语义，不向操作者展示 Mapper、事务和 Schema 库细节。

## 2. 状态所有权

| 状态 | 所在位置 | 更新原则 |
| --- | --- | --- |
| RoomView、实时 Match、服务端配置 | 页面 useReducer + Context | REST 是权威；按 roomRevision 接受较新结果 |
| Prompt 未保存文本、dirty、保存中 | 各 AgentPanel 局部状态 | SSE/查询不覆盖 dirty 文本 |
| 已保存草稿 revision / 发布版本 | 服务端缓存 | 保存成功更新 revision，发布成功更新 published 指针 |
| 连接状态、最后处理 SSE sequence | 连接 hook | 去重、重连、重同步；不当游戏状态 |
| 历史 Match、replayVersion | HistoryPanel | 独立于实时 Match；仅查询快照与记录 |
| 正在提交的 commandId | 命令 hook | 网络重试复用原请求；新点击生成新 ID |

无需 Redux/Zustand 或持久聊天 Session。API types 后续由 OpenAPI 生成或手工对应并契约检查，禁止扩展出与 OpenAPI 不同的状态枚举。

## 3. Prompt 与模型设置

- 编辑仅改变本地文本；点击保存发送 DraftUpdate。保存期间继续编辑时，用请求时文本快照判断响应是否仍对应当前文本，避免把新文本误标已保存。
- 点击“应用 Prompt”先保存当前文本，获得最新草稿 revision，再发送 PublishPrompt；保存失败或冲突则停止发布，不能发布用户未确认的远端草稿。
- 显示“未保存”“已保存，尚未应用”“当前发布 vN”；若有 RUNNING 决策，另显示“当前决策使用 vM，新版本用于下一次决策”。
- 草稿冲突时保留本地文本，显示远端文本供核对，允许重新载入或在最新 revision 上再次保存；不自动覆盖任一方。
- Prompt 发布和模型配置各自形成版本，配置保存也只影响尚未启动的决策。运行中的请求配置快照可在详情查看。
- Runtime 选择 Mock/LLM；LLM 模型默认 deepseek-flash，普通界面默认 JSON_MODE，不提供未实现的 MemoryMode 控件。严格 Tool Calling 属显式高级配置，需 allowBeta=true。
- 缺 Key 显示服务端配置错误，不在网页输入/存储真实 API Key，不把 Key 放 localStorage。页面可显示配置是否可用，不返回凭据值。

## 4. 控制交互

| 控件 | 可用条件 | 用户可见反馈 |
| --- | --- | --- |
| 开始 | READY | 自动运行，立即显示请求处理中 |
| 下一步 | READY/ACTIVE 且 PAUSED | 执行当前一方一次决策与结算 |
| 暂停 | ACTIVE/RUNNING | 有决策时显示“当前决策结束后暂停” |
| 自动/单步模式 | 非终局 | STEP 只切模式并停住，不隐式授予下一步 |
| 继续 | ACTIVE/PAUSED | 切 AUTO 并继续 |
| 中止当前决策 | 有活动决策 | 显示“当前动作将被作废，棋盘保留”，成功后暂停 |
| 重置 | 任意健康状态的当前局 | 提示新建比赛、保留配置与旧局；中止旧决策 |

按钮禁用只是体验优化，服务端仍校验。UI 不缓存单步许可，不排队重复点击；CONTROL_BUSY 表示本次没有多走一步。控制请求携带查询得到的 expectedMatchId、expectedRoomRevision。遇到 revision 冲突刷新后显示当前状态，不自动重放新的控制意图，尤其不自动再发 step/reset。

网络响应丢失时只用相同 commandId/body 重试一次；继续失败保留不确定状态并刷新。请求响应携带的较旧 RoomView 不覆盖 SSE 后刷新得到的新 revision。终局、恢复故障和回放视图禁用推进操作。

## 5. 棋盘与决策展示

ConnectFourBoard 根据 gameType 从注册表选择；读取 ConnectFourBoardView：6×7 board、lastMove、winningCells；渲染层不调用规则决定落点或胜负。A/B 用颜色加字母，避免只依赖颜色；高亮最后落子与获胜连线。棋盘没有手工落子入口。

Agent 面板显示当前模型/发布版本、最近 Decision ID、决策状态、耗时、可获取 Token 用量、模型解析出的候选动作、最终执行动作和校验错误。缺失用量显示“未提供”，不能显示为 0。模型返回内容以转义文本展示，不用 HTML 注入。

DecisionInspector 分开列出：观察快照、绑定配置与 PromptVersion、实际输入（操作者私有详情）、原始动作输出、解析动作、校验结果、执行回执。失败没有执行动作；取消/过期后的迟到内容仅作诊断，不显示成棋盘落子。

## 6. SSE、刷新与回放

1. 首次 GET RoomView，保存 eventCursor；以 after 建 EventSource。
2. 对 sequence <= 已处理游标的事件去重；事件导致查询缓存失效，合并刷新请求。
3. 拉取结果必须检查 roomId/currentMatchId/revision；旧 Match 结果不得覆盖重置后的棋盘。
4. 临时断线由 EventSource 携带 Last-Event-ID 重连；显示断线标记，未知状态时禁用推进按钮。
5. 游标非法、重复连接失败或数据不一致时关闭旧连接，重新 GET，再从新 cursor 建连接。未保存草稿保留。

历史选择器通过 MatchPage 分页；进入回放使用所选 Match 的快照 0，前后步按 stateVersion 移动至最终版本，关联该步的 Decision 和 GameEvent。失败/取消 Decision 可以查看但不增加棋盘步数；同一步可能有多个失败尝试。

回放时实时 SSE 继续维护后台 live 状态，但不抢回历史视图；控制操作禁用并显示“返回实时”。回放只读数据库，不重调模型、不改变 Prompt。刷新页面后先恢复实时视图，本地未保存文本可在组件存活期间保留，不承诺关闭浏览器后的草稿恢复。

## 7. 前端验收

可仅使用 Mock 完成整局、应用 Prompt、暂停、单步、错误恢复、重置与回放；慢请求、旧响应、断线重连不回滚状态；保存中编辑不丢字；键盘可访问控制与表单；不显示未实现多人/Memory/工具控件。真实 LLM 验收另行进行，不让 CI 依赖网络或计费 Key。
