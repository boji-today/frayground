# 分阶段实施计划

> 本文件是下一轮代码生成入口。当前只交付设计，不代表阶段已经实现。先读[纲领](PROJECT_CHARTER.md)、[范围](01-product-scope.md)、[ADR](09-architecture-decisions.md)，再按顺序执行。不得顺带安装依赖、提交或推送；涉及下载、容器或付费调用时遵守会话授权与项目协作要求。

## 1. 实施约定

- 后端单 Maven 工程 `backend/`，根包 io.frayground；前端 `frontend/`。不提前创建空模块、远程 SDK 或第二款产品游戏。
- OpenAPI 是 HTTP DTO 的来源；Java 领域以纯类型表达规则，Mapper Entity/DTO 分离。新增接口不得自行更改控制语义。
- 每阶段完成相关测试与文档同步后再推进；失败先报告阻塞点，不通过削减范围或伪造结果验收。
- 骨架命令：`backend` 下 `./mvnw test`、`frontend` 下 `npm install` / `npm run build`、根目录 `docker compose up --build`。业务 API、MySQL 与实验室页面仍未实现。
- 所有数据库变更同步完整 schema.sql/data.sql；只在空数据卷初始化，无 Flyway/Liquibase，已有库改动人工审阅执行。

## 2. 阶段依赖

```mermaid
flowchart LR
  P1[1 骨架/SQL] --> P2[2 规则/Codec]
  P2 --> P3[3 持久化/Prompt]
  P3 --> P4[4 调度/Mock/恢复]
  P4 --> P5[5 REST]
  P5 --> P6[6 LLM/Context]
  P6 --> P7[7 SSE/历史]
  P7 --> P8[8 前端]
  P8 --> P9[9 E2E/验收]
```

持久化提前于调度：避免先实现一套内存语义，之后重写幂等、暂停与恢复。当前新增通用内存存储仅用于开发/测试运行模式；正式 database 模式仍需实现 MyBatis 持久化适配器。

### 已确认的任务 3 补充：通用存储与内存事务模式

本任务在不改变既有业务端口和领域对象的前提下，为 `application.port` 增加通用存储扩展，并新增共享 `InMemoryStorage` 状态镜像与 Spring `gameTransactionManager`。内存事务以 Spring 事务代理为入口，仓储写入必须在事务内；database 模式仍由 `transactionManager` 提供 JDBC 默认事务语义，当前没有数据库仓储实现，也没有内存/数据库混合原子事务或运行时模式迁移。`dev` profile 默认选择 memory，基础配置选择 database，可被外部配置覆盖。

## 3. 阶段一：工程、环境与完整 SQL

**前置：**已确认设计；联网下载/容器使用获得所需授权。

**文件与模块：**根 `Dockerfile`、`compose.yaml`（默认文件名，不必 `-f`）、`.gitignore`、`.dockerignore`；`backend/pom.xml`、Maven Wrapper、Application、application.yml、`src/main/resources/static/`；`frontend/` Vite 占位；`backend/src/main/resources/db/schema.sql`、`data.sql`；不创建业务空类。

**内容与接口：**Java 21、Boot 4.1.1、MyBatis-Plus 3.5.17 Boot4 starter；JUnit6/Testcontainers2；Jackson3/Validator3。核验 MySQL 8.4 精确镜像补丁/摘要、JDBC 驱动和 JSON TypeHandler 依赖。应用 `spring.sql.init.mode=never`，显式 UTC、utf8mb4 和 loopback。Compose 挂载正式两份 SQL，只有首次空卷执行；种子两 Agent/初始 Prompt/Mock 配置/单 Room，无伪造比赛历史。

**测试/集成：**数据库临时容器执行完整两脚本、关键表/生成列/外键存在；同卷重启不会重放；应用不执行 SQL、不覆盖人工修改。依赖树不能混入 Boot3 starter 或意外 Jackson2 TypeHandler。

**完成：**骨架可编译启动；所选版本和实际运行命令写入 README；本机缺环境明确报告，不自行大范围换栈。MySQL 服务、MyBatis-Plus 与空卷初始化仍待本阶段后半完成。

## 4. 阶段二：领域与游戏规则

**前置：**阶段一编译/测试基础。

**文件与模块：**`domain/` 的 ID、状态、参与者、TurnPlan/Resolution/GameResult；`games/connectfour/` 的 State/Action/Observation/Definition；`adapter/game/` Codec/Schema/Registry；测试下 TakeAwayDefinition 与夹具。

**核心接口：**GameDefinition、GameCodec、ActionSpecification、ActionParser/Validator；见[领域草案](03-domain-model.md)。

**内容：**不可变 7×6 四子棋，A 先手，候选 c0..c6；动作 Schema 与动态规则分离；显式注册 rulesVersion=1，按注册 Codec 解码，禁用任意类加载。

**测试/集成：**落子、满列、边界、四方向胜利、平局、终局、观察复制；同一通用驱动运行无棋盘 TakeAway。Schema 正负例测试。

**完成：**纯规则不启动 Spring、不导入 Jackson/MyBatis/模型；第二游戏不修改通用引擎、不注册到产品菜单。

## 5. 阶段三：MyBatis-Plus 持久化、Prompt 与幂等基础

**前置：**阶段一 SQL、阶段二 Codec。

**文件与模块：**`application/port/` Repository；`adapter/persistence/entity/`、`mapper/`、Mapper XML、JsonTypeHandler；PromptService、ConfigurationService、SnapshotStore、CommandReceiptRepository、ActionReceiptRepository；相关真实数据库测试。

**核心接口：**RoomRepository.lock、MatchRepository.compareAndSetVersion、DecisionRepository.transition、CommandReceiptRepository、ActionReceiptRepository、PromptPublisher；方法必须表达事务行为，不能靠 BaseMapper.saveOrUpdate 完成结算。

**内容：**映射[完整表设计](05-persistence.md)，显式锁顺序、条件更新行数断言；不可变 Prompt/Config 版本和草稿 CAS；commandId/actionId 规范化哈希；已拒绝命令回执；初次 Room 无 Match 时原子创建 READY Match、席位、Turn、快照0。

**测试/集成：**JSON 回读、生成列活动唯一键、重复回执、并发发布、草稿冲突、失败事务全部回滚；启动两次不重复初始化 Match。

**完成：**数据库真实约束验证通过；普通代码无更新历史 Prompt/Config 的入口；凭据值不入库。

## 6. 阶段四：调度、Mock 与恢复

**前置：**阶段二规则、阶段三事务基础。

**文件与模块：**`engine/TurnScheduler`、GameEngine、RoomCommandQueue、DecisionLifecycle；`agent/AgentRunner`、AgentRuntime、MockAgentRuntime、CancellationSignal；`application/ActionSubmitter`、RecoveryService。

**核心接口：**scheduleTick、handleCommand、start、submit、fail/expire/cancel、recoverBeforeReady。

**内容：**实现 AUTO/STEP 与状态分离；PENDING/RUNNING 绑定点；30 秒服务端期限；单步无累积；决策边界暂停；独立中止；重置旧局隔离；原子结算与回执；提交后另排 tick；恢复取消未完成决策并暂停。

**测试/集成：**可控 Future/Clock 覆盖全部控制竞争；真实 MySQL 双连接并发提交、超时竞争、提交/响应故障；两 Room 测试夹具证明队列隔离。

**完成：**无需 LLM 完整 Mock 对局；无持锁等待；每个成功世界变化都有同事务记录；取消和旧响应不落子。

## 7. 阶段五：浏览器 REST 与错误契约

**前置：**阶段四可运行闭环。

**文件与模块：**`web/` DTO、Controller、ExceptionHandler、Origin/Header Filter、RateLimiter；管理面 QueryService/Projection；OpenAPI 响应契约测试。

**核心接口：**OpenAPI 中 planned-mvp 的非 SSE 路径；控制统一 commands 接口，ActionSubmitter 仍为内部入口。

**内容：**请求大小、UUID、版本、JSON 格式校验；控制回执 HTTP 原样重放；同步错误与异步模型失败分开；分页和私有审计投影；无通配 CORS。

**测试/集成：**MockMvc 对全部 planned 路径的正常/拒绝响应验证 Schema；future 路径不存在；同源 Header、限流、错误脱敏；step 请求重放不增加许可。

**完成：**脚本/API 客户端可配置双方并完成 Mock 对局；响应与 OpenAPI 对齐；不新增手工替 Agent 落子接口。

## 8. 阶段六：Stateless、DeepSeek 与输出校验

**前置：**阶段四 Runner、阶段五查询可观察。

**文件与模块：**`agent/context/StatelessContextBuilder`、SelectionParser、SchemaValidator、ActionPreflight；`adapter/model/DeepSeekModelAdapter`、MockModelAdapter、ModelCapabilities；模型错误映射与脱敏。

**核心接口：**ContextBuilder.build、ModelAdapter.invoke/capabilities、RuntimeOutcome；见[协议与记忆](10-agent-protocol-and-memory.md)。

**内容：**消息由固定快照构建并先保存再调用；JSON_MODE 默认，capabilities 选择输出方式；非思考、256 输出 Token、5 秒连接/30 秒总期限、32KiB 输入；无模型自动重试；提交传输重试复用 Envelope；只接受 STATELESS。

**测试/集成：**本地 HttpServer 模拟正常/空/截断/非法/拒绝/429/5xx/超时；selection 动态 enum；无 Key 明确失败；所有输入不含历史聊天/对手配置/Key/reasoning。真实调用为独立 opt-in smoke，未授权或无 Key 不执行。

**完成：**Mock HTTP 测试无需外网；实际合法动作与模型候选分开记录；更换 ModelAdapter 不改规则/调度。

**当前进度：部分完成。** DecisionRequest JSON 形状解析、`stateless-v1` 上下文构造、确定性 Mock Runtime 和 ACTION_SELECTION 的 Runner 编排与权威提交已实现。LLM Runtime、模型输出解析与验证、调用前模型审计接入及完整 STRUCTURED_ACTION 链路仍未实现；阶段未整体完成。

## 9. 阶段七：SSE 与历史

**前置：**阶段三 RoomEvent、阶段五 API、阶段六 DecisionRecord。

**文件与模块：**RoomEventFeed、SseController、EventProjection、HistoryQuery、SnapshotQuery；持久化游标及 HTTP 流测试。

**核心接口：**room snapshot+cursor、readEventsAfter、subscribe；历史局、GameEvent、Decision、指定快照查询。

**内容：**每250ms批量读取已提交事件、15秒心跳、有界慢连接；Last-Event-ID补发；不推模型 Token 流；历史投影分权限；每成功棋步快照和事件重建核对。

**测试/集成：**查询→订阅窗口、重连去重、回滚不可见、恢复补发、非法游标、慢连接；事件重建与快照一致；历史读取不触发模型。

**完成：**浏览器刷新/断线后能恢复权威状态，无数据库到推送的不可恢复空窗。

## 10. 阶段八：React 实验室

**前置：**阶段五/七稳定接口。

**文件与模块：**`frontend/package.json`、Vite配置、API types/client、useRoom/useRoomEvents、reducer；AgentPanel、PromptEditor、MatchControls、ConnectFourBoard、DecisionInspector、HistoryPanel；组件测试。

**核心接口：**planned-mvp DTO，组件 state 不复用服务器 Entity。

**内容：**三栏响应式布局；保存后发布；草稿dirty不被SSE覆盖；控制busy/暂停请求显示；模型候选/执行动作分开；历史局逐步回放、返回实时再操作；不增加 Memory/多人菜单。

**测试/集成：**保存中编辑、冲突保留、旧响应过滤、重置隔离、SSE重连、回放不被实时推送抢占；前后端Mock整局联调。

**完成：**桌面无需API工具完成闭环；键盘可达、颜色之外区分双方；无真实Key进入构建产物。

## 11. 阶段九：系统验收

**前置：**阶段一至八。

**文件与模块：**`frontend/e2e/`、后端故障恢复集成测试、项目实际启动说明、验收报告；不实现额外功能。

**内容：**执行[最小E2E](07-testing.md)，复核依赖方向和三场景扩展矩阵；测试数据卷初始化/复用、提交故障/响应丢失、重启暂停；将真实LLM smoke与自动离线验证分列。

**完成：**产品范围验收表逐项有证据，失败和未验证项明确；OpenAPI/SQL/文档与代码一致；没有未来接口意外开放或缺陷被Mock掩盖。此阶段结束仍不自动部署、提交或推送。

## 12. 明确延后

仅保留契约：STRUCTURED_ACTION 产品能力、远程 Pull/Push、RECENT_HISTORY/STRATEGY_MEMORY、同时回合阶段收集。完全延后：SDK发布、MCP/A2A、工具市场、Prompt A/B 调度、永久回放服务、账号体系、排行榜、跨局记忆和分布式设施。

后续资源游戏提议：四个席位在有限轮次分配资源并统一结算，简单确定性收益/竞争约束，观察动作分布和策略差异。须先定义玩法与实验条件，再批准范围，不作为本轮或MVP隐含任务。
