# 测试策略与设计验收

> 前半部分记录设计期测试规范及 2026-10-19 文档检查。backend 闭环实现后的实际验收结果见第 10 节；未列为实际执行的用例仍是后续范围。

## 1. 工具与夹具

当前 backend 的 laboratory profile 使用内存 Store。`LaboratoryGameHttpAcceptanceTest` 使用固定七手 Runtime fixture 通过公开随机回环 HTTP API 验收控制流程与历史；`LaboratoryDefaultMockHttpAcceptanceTest` 使用生产默认 Mock Runtime 验收完整对局、单步/继续和重置。`LaboratoryDeepSeekLoopbackAcceptanceTest` 将 DeepSeek endpoint 指向本机临时 HTTP server，使用生产 adapter 与两个 LLM 绑定跑完一局。离线验收替换 DataSource 并断言没有数据库连接交互，不构成真实 DeepSeek 验证。

真实模型调用须作为手动 opt-in 单独执行；不得将其作为默认测试或 CI 前置条件。

- JUnit Jupiter 6，纯规则测试不启动 Spring；Scheduler 使用可控 Clock、手动推进的 Future 和测试 Executor，不依赖 Thread.sleep 碰运气。
- MySQL 8.4 Testcontainers 与 Compose 使用相同补丁镜像；复用正式 schema.sql/data.sql；不以 H2 替代锁、JSON、生成列唯一键和事务测试。
- 生产 Mock Runtime 固定选择候选列表首项，不判断规则合法性；测试 fake 覆盖固定动作序列、首个合法动作、延迟完成、拒绝、异常和永久不完成。MockModelAdapter 返回固定 HTTP 风格结构。
- 模型适配器用本地 JDK HttpServer 模拟 DeepSeek 响应；不新增真实模型调用作为 CI 前置条件。
- 前端组件用 Vitest/Testing Library；最小端到端使用 Playwright，浏览器服务、MySQL、Mock Runtime 组成完整闭环。
- 第二游戏 TakeAway 仅测试注册，覆盖无 board/column 的状态与动作。

## 2. 规则单元测试

| 场景 | 断言 |
| --- | --- |
| 空棋盘落子 | 所选列最低空格写入正确席位，旧 State 未变化 |
| 同列连续落子 | 从下向上堆叠，满列第七次拒绝 |
| 非法列 | -1/7、非整数或错误类型在对应层拒绝 |
| 非法席位 | 非行动方、不属于 Match、人数不符拒绝 |
| 胜利 | 横、竖、两斜向；边界连线；大于四连；不跨行误连 |
| 第 42 手 | 若连线则 WIN 优先，否则 DRAW |
| 终局 | 无合法动作，不再调度或接受 resolve |
| Observation | 棋盘复制、席位正确、合法列仅未满列 |

固定胜利序列（列号 0 基）：A 横胜 `0,6,1,6,2,5,3`；A 竖胜 `0,1,0,1,0,1,0`。斜向用状态工厂构造重力合法局面，并检查落子前尚未终局。

平局夹具可从下至上交替生成 `A A B B A A B` 和 `B B A A B B A` 的 6×7 满盘：双方各 21 子，横向最多两连、竖斜无四连。验证最后一手前状态合法且不终局，不使用双方棋子计数不符的假平局。

## 3. 调度与控制测试

| 场景 | 操作控制 | 断言 |
| --- | --- | --- |
| 交替/自动运行 | Future 按测试顺序完成 | 席位 A/B 轮换，直到终局停止 |
| 推理中暂停 | 保持 Future 未完成，执行 pause，再完成 | 状态先 PAUSING，当前落子一次后 PAUSED，未调对方 |
| AUTO→STEP | 同上改模式 | 不取消、不丢动作，结束后必须再点 step |
| 连续 step | 并发/不同 commandId 点击 | 只启动一个 Decision；其余 busy/版本冲突，无许可累积 |
| step 网络重试 | 完成前及完成后重放同 ID | 原命令回执，无第二次决策 |
| busy 回执重试 | 第一次 busy，之后暂停再重试同 ID | 仍 busy，不延迟执行 |
| pause 与 tick 竞争 | 用 latch 控制两种锁顺序 | 已先启动的是当前决策；pause 提交后不能有新启动 |
| set_mode AUTO | 在 PAUSED/PAUSING 切换 | 不隐式解除暂停；在 RUNNING 切换可按规则继续 |
| 失败/超时 | 注入错误/推进 Clock | FAILED/EXPIRED，棋盘不变并 PAUSED |
| 中止/迟到 | cancel 后再完成 Future | CANCELLED 保持，棋盘不变，无成功事件 |
| reset/旧 tick | reset 后投递旧响应和旧 tick | 不影响新 Match；配置保留，新席位 ID |
| 终局与暂停重叠 | 当前落子获胜 | Match=FINISHED、Room=PAUSED，且不再调度 |
| 房间隔离 | 测试创建两 Room，一方 Future 挂起 | 另一房间可控制、提交；无全局锁阻塞 |

## 4. Prompt、配置与 Stateless

- 草稿保存不移动发布指针；发布生成递增且不可变版本；相同发布命令只生成一个版本。
- 以启动事务为线性化点：分别测试 publish 在 RUNNING 前/后，Decision 绑定对应版本；PENDING 不提前固定旧版本。
- 推理中更新模型参数同样不改变当前配置快照；DecisionRecord 可反查实际 Prompt/Config。
- 每次独立构造消息，默认无历史 assistant 消息、无上一局上下文、无对手 Prompt/Key/reasoning。
- ContextBuilder 读取固定 Observation，不在调用期间追随最新状态；超过字节预算明确失败，不截断。
- STATELESS 不调用 VisibleHistoryReader/StrategyMemoryReader；其他 MemoryMode 的产品请求明确拒绝。
- 历史可见性用“隐藏事件”的测试 GameDefinition：外部投影无法读完整权威事件和私有记录。

## 5. 输出与提交协议

合法 selection / payload；空输出、无效 JSON、代码块、多对象、重复 key、缺字段、错误类型、额外字段、未知枚举、非法选择 ID、满列、越界、拒绝、截断、多个 tool calls、429、5xx、超时分别归一化为明确错误。JSON Mode 能力路径及不支持 native Schema 的预选降级用 Mock 覆盖；不因失败再调一次模型。

| 提交场景 | 数据库断言 |
| --- | --- |
| 同 ID 同内容重复、JSON key 顺序不同 | 原回执，settlement/棋盘/事件只增加一次 |
| 同 ID 不同内容 | ACTION_ID_CONFLICT，原回执不被改写 |
| 同 Decision 不同 actionId | 一次成功，其余 DECISION_ALREADY_RESOLVED |
| 失败后同阶段新 Decision | 保留多个尝试，活动决策最多一个，阶段只成功一次 |
| 并发相同/不同 ID | 用不同连接测试；唯一键和 Room 锁共同防重复 |
| 到期边界 | now=deadline 拒绝；超时任务与提交仅一个终态 |
| 旧 Match、旧 Turn、旧 stateVersion | 无新世界变化；拒绝回执或冲突准确 |
| 不匹配 Participant | 不使合法正在运行 Decision 失败 |
| 成功后响应丢失再重试 | 查回执成功，即使当前 Match 已重置 |
| 数据库故障重试 | 无伪成功回执；仅重试事务/提交，不重发模型 |

生成列唯一约束必须以真实插入证明：两个活动状态冲突，多个终态记录共存。不能只断言 Mapper 被调用一次。

## 6. 持久化与恢复

- 在“写回执后、更新 Match 后、写事件后、commit 后但响应前”注入故障，核对事务要么全有要么全无。
- 快照的每一版本与事件重建棋盘一致；初始版本 0 存在；非法动作不增加成功版本/落子事件。
- 重启前已有 COMPLETED 与 RUNNING：完成者不重复执行，未完成者取消并暂停，旧响应无效。
- 未知规则版本、缺失快照、数据库不可用：拒绝推进且故障可见，不自动重建/删除数据。
- 用完整初始化 SQL 建空库；复用同一数据卷重启容器，检查自定义 Prompt、记录不变；应用启动 `spring.sql.init.mode=never`。
- 检查 schema/data 无真实密钥，配置只存 credentialRef；初始 MOCK 无网络调用。
- 检查 JSON TypeHandler 读写不可变对象；配置/Prompt 历史不能经普通更新接口覆盖。

MySQL DDL 不承诺整份脚本回滚；模拟首次初始化中断时，应报告缺表，不启动业务，也不自动删除卷。备份恢复另在明确目标的手工演练中验证。

## 7. REST、SSE 与 E2E

REST 用 MockMvc 验证正常 DTO、拒绝路径、版本冲突、命令回执、Origin/自定义 Header、限流和错误不泄密；所有响应对 OpenAPI Schema 做验证。管理端不得部署 future 路径；无凭据值出现在 AgentView。

SSE 用实际 HTTP 流测帧格式、单调序号、Last-Event-ID 优先级、重复事件去重、初始化查询到订阅期间事件不丢、慢连接断开与重连、无 ID 心跳。事务回滚事件不可见，进程恢复后能补发已提交事件。

最小 E2E 用例：

1. 空库首次启动 → 加载双方 → Mock 单步仅一子 → 自动走到终局 → 重置新局且旧局可回放。
2. 延迟 Mock 推理期间改 Prompt 并切 STEP → 当前旧版本落子一次后暂停 → 下一步使用新版本。
3. 输出错误暂停 → 修正配置并下一步 → 成功；刷新/断线后状态一致且 dirty 文本不被推送覆盖。
4. 重启恢复暂停 → 取消前旧请求不落子 → 人工继续；历史审计可关联整个过程。

真实 DeepSeek 为人工 opt-in 完整对局 smoke，最多 42 手，记录结果、耗时和汇总用量；没有 Key 或网络权限时明确未执行，不能将模拟通过写成真实 API 通过。

## 8. 策略实验与交付检查

Prompt Sensitivity Test 的未来研究方案：固定相同 Observation/规则/候选/模型配置，分别使用 v1/v2，多次采样；记录别名实际模型与日期；交换先后手进行多局比较。动作分布、非法率、延迟与最终胜率分别报告；温度 0 仍不保证确定性，不宣称单局差异证明策略提升。MVP 只保证记录可追溯，无实验调度器。

文档阶段检查：Markdown 本地链接、OpenAPI YAML 解析、内部 `$ref`、operationId 唯一性、路径参数、43 个 Schema 的 2020-12 合法性、示例与负例校验、术语/状态/SQL/端点交叉核对。若 OpenAPI 专用校验器或 Mermaid 渲染器本机没有，不自动安装；明确检查范围，不能声称已执行渲染或真实数据库测试。

## 9. 本轮文档验证记录（2026-10-19）

使用本机已有 Python、PyYAML 与 jsonschema 完成以下只读校验，均通过：

| 检查 | 结果 |
| --- | --- |
| YAML 重复键、解析 | 无重复键，可解析 |
| 路径、操作、路径参数 | 20 个路径、22 个操作（含远程投递 webhook），operationId 唯一、路径参数齐全 |
| JSON Schema | 43 个定义通过 Draft 2020-12 元 Schema 校验 |
| 内部引用 | 238 处本地引用均可解析 |
| 正例 | 18 个命名示例及内嵌 Schema 示例通过对应 Schema |
| 反例 | 34 个非法输入按预期拒绝，包括缺字段、类型/范围、错误模式、无 Beta 授权、矛盾回执和无效棋盘 |
| Markdown | 52 处本地文档链接存在，代码围栏闭合，7 个 JSON 代码块可解析 |
| SQL 草案 | 16 张表的外键目标均有定义；未出现 DROP/TRUNCATE/JSONB；这是静态核对，不是 MySQL 执行验证 |
| 平局夹具 | 双方各 21 子，无横竖斜四连；仅验证文档给出的满盘事实，未声称完成规则实现测试 |

截至 2026-10-19 的文档阶段检查：本机未安装 OpenAPI 专用验证器或 SQL 解析器，本轮未安装依赖。该阶段未执行完整 OpenAPI 专用校验、MySQL DDL/容器测试、Java 依赖解析、Mermaid 渲染、真实模型调用或业务 E2E；后续实际实现验收见第 10 节。

## 10. Backend 内存对局闭环（2026-10-29）

内存闭环验收包含：`LaboratoryGameHttpAcceptanceTest` 以 7 手胜局序列验收 HTTP 控制、终局记录和历史查询；`LaboratoryDefaultMockHttpAcceptanceTest` 使用生产默认 Mock runtime 执行初始化、单步暂停、继续终局和重置；`LaboratoryDeepSeekLoopbackAcceptanceTest` 使用真实 DeepSeek adapter 和本地 fake HTTP 服务验收完整局、请求 prompt/候选、暂停、HTTP 错误、非法候选、继续及 reset 后迟到响应处理；`LaboratoryMixedAgentConfigurationTest` 检查双侧配置绑定，`LaboratoryMixedRuntimeHttpAcceptanceTest` 实际运行 A=MOCK/B=LLM 混合对局；`DeepSeekLiveGameSmokeTest` 默认跳过，只有显式 opt-in 才会连接真实 DeepSeek。离线 Spring 验收使用 mock DataSource 并断言未访问数据库。

2026-10-30 离线定向与回归合并执行：

```bash
cd backend
./mvnw -o -q -Dtest=LaboratoryGameHttpAcceptanceTest,LaboratoryDeepSeekLoopbackAcceptanceTest,LaboratoryDefaultMockHttpAcceptanceTest,LaboratoryMixedAgentConfigurationTest,LaboratoryMixedRuntimeHttpAcceptanceTest,DeepSeekLiveGameSmokeTest,LaboratoryApiIntegrationTest,LlmDecisionExecutionIntegrationTest,DeepSeekModelAdapterTest,ModelConfigurationYamlTest,SystemModelCredentialProviderTest test
```

共 32 项：31 passed、1 skipped、0 failed；跳过项是默认关闭的真实 DeepSeek smoke。随后抽取辅助类后，使用 `./mvnw -o -q -Dtest=LaboratoryDeepSeekLoopbackAcceptanceTest,LaboratoryMixedRuntimeHttpAcceptanceTest,LaboratoryGameHttpAcceptanceTest test` 复跑，4 项全部通过。没有执行真实 DeepSeek 请求。

真实调用仅在明确授权后手动执行，会产生模型调用费用。设置 `DEEPSEEK_API_KEY`（或原配置 `FRAYGROUND_MODEL_SYSTEM_API_KEYS_DEEPSEEK_FLASH`）后运行：

```bash
cd backend
FRAYGROUND_RUN_DEEPSEEK_SMOKE=true ./mvnw -Dtest=DeepSeekLiveGameSmokeTest test
```

该 smoke 复用 `LaboratoryGameHttpClient` 和 `LaboratoryGameSmokeRunner`，每次本地 HTTP 请求的超时取 5 秒与剩余期限的较小值。23 分钟覆盖测试方法内的初始化、控制、轮询和最终记录查询，不包含 Spring 上下文启动及关闭。结束时统一输出成功状态、错误码、局 ID、结果、步数、耗时和用量；网络或期限失败时，使用最后成功读取的记录快照，并标明 `usageSource=lastSuccessfulPoll`。尚未读到记录时标明 `usageAvailable=false`，数值不代表已确认的真实用量。摘要不输出响应正文、密钥或私有 Prompt。离线 loopback 测试使用本机模拟服务，不会调用真实 DeepSeek。

### 10.1 验收缺口补齐（2026-10-30）

- `LaboratoryGameAssertions` 通过公开 HTTP API 以每页 2 条检查历史：Decision 唯一性、时间顺序、Match/Participant 身份、详情与摘要一致、动作与回执一致、事件与 Decision/席位对应，并用 `DiscPlaced` 重建最终棋盘。
- 重置检查双方 Participant 全部更新、双方 Agent 与席位保留、Room 保留及 READY/STEP/PAUSED；重放旧命令后新房间状态不变，旧 Match 完整结果及所有公开决策、详情和事件与重置前相同。
- 生产 Mock 测试逐格检查初始空棋盘和单步一子，自动结束后持续观察 600ms，确认没有新增决策或状态变化。双 LLM 测试核对实际 Participant、Prompt/配置版本、动作、回执及用量；混合 Runtime 测试核对双方身份、LLM 请求的 Prompt/候选/动作及双方不同的用量记录。错误后的手动 continue 必须使用新 Decision ID，并保留失败记录。
- 双 LLM 和混合 Runtime 共用独立实例的 `LocalDeepSeekServer`；所有对局 HTTP 测试共用 `LaboratoryGameHttpClient`。`LaboratoryGameSmokeRunnerTest` 在本机模拟 API 上覆盖成功、模型失败、HTTP 错误、请求挂起、轮询等待耗尽总期限、请求进行中耗尽总期限六条路径，验证摘要和没有自动重试/continue。

最终离线验收与相关回归命令：

```bash
cd backend
./mvnw -o -q -Dtest=LaboratoryGameHttpAcceptanceTest,LaboratoryDeepSeekLoopbackAcceptanceTest,LaboratoryDefaultMockHttpAcceptanceTest,LaboratoryMixedAgentConfigurationTest,LaboratoryMixedRuntimeHttpAcceptanceTest,LaboratoryGameSmokeRunnerTest,DeepSeekLiveGameSmokeTest,LaboratoryApiIntegrationTest,LlmDecisionExecutionIntegrationTest,DeepSeekModelAdapterTest,ModelConfigurationYamlTest,SystemModelCredentialProviderTest,DecisionExecutionIntegrationTest,LaboratoryRoomInitializerTest test
```

最终执行结果：154 项，153 passed、1 skipped、0 failures、0 errors。包含既有 `DecisionExecutionIntegrationTest` 的 114 项测试，以及新增的 6 项离线 smoke 流程测试。跳过项仍为未开启的真实 DeepSeek smoke；没有向真实模型服务发送请求。`git diff --check` 通过。
