# 架构决策与批判性审查

> 2026-10-19。下列 ADR 为当前已采纳设计，依据本次负责人确认的修订计划；不是实现验收。为避免重复，本阶段将 ADR 集中于本文件，不再建立平行副本。未来拆分时本文件改为索引并保留 ID/取代关系。

## 1. ADR 记录

每条记录的日期均为 2026-10-19，类型均为架构决策；状态“已采纳”，除标注外均无被取代记录。表中“替代方案”为评估而未采用，不等于删除已实现能力（当前无业务实现）。

| ID / 主题 | 背景与替代方案 | 决定与理由 | 影响、复审条件与取代关系 |
| --- | --- | --- | --- |
| ADR-001 Java / Boot / 单工程 | 用户熟悉 Java；Python/Rust 或多 Maven 工程可行 | Java21/Boot4.1.1，模块化单体、包边界；降低审阅与跨语言成本 | 需依赖方向测试；有独立部署/团队边界证据再拆；无前序 |
| ADR-002 MySQL / MyBatis-Plus | 初始建议 PostgreSQL/JDBC/Flyway；用户明确更改 | MySQL8.4 LTS + MP3.5.17 Boot4；Entity在适配层，显式事务SQL | 与MySQL方言绑定仅限持久化；取代未落地的PostgreSQL/Flyway建议，不存在数据迁移 |
| ADR-003 完整 SQL 维护 | 自动迁移或应用每次启动改表均有其他取舍 | schema.sql/data.sql完整来源，空卷首次初始化，应用init=never；无迁移框架 | 既有库需人工备份/精确变更，不支持任意旧版本自动升级；多个发布环境时复审 |
| ADR-004 REST + SSE | WebSocket双向协议可行，当前浏览器只需命令和观察 | REST请求 + 持久化SSE补发，简化命令回执和刷新恢复 | 需要双向实时高频输入时复审；无前序 |
| ADR-005 游戏插件与强类型 | 每游戏写房间引擎简单但会复制调度；大规则插件系统过重 | 单GameDefinition + Registry/Codec，State/Action/Observation分离 | 第二游戏夹具验证，无动态不可信代码；多人/持续世界可合理改调度 |
| ADR-006 参与席位集合 | 1v1固定双字段会把当前玩法写入平台 | Room/Match用集合，人数约束由四子棋规则检查 | 不实现多人产品；未来人数扩展无需改身份模型 |
| ADR-007 Runtime / Runner / ModelAdapter | 绑定LLM框架或把提交交给模型都会扩大耦合 | Runtime返回候选，Runner编排，ModelAdapter隔离厂商，Submitter权威结算 | 新供应商改Adapter，复杂Agent改Runtime；不会改游戏规则 |
| ADR-008 直接HTTP模型接入 | Spring AI/LangChain4j均有Boot4路线 | 小契约便于审阅能力/重试/用量，不把模型调用交给 LLM 框架 | 需自行做错误映射；JDK HttpClient 这一传输选择由 ADR-016 取代；多工具/多模态工作流确有需求再评估框架，不以“不兼容”误拒 |
| ADR-016 每服务商异步连接池 | JDK HttpClient 不能按服务商限制物理连接数，默认完成线程无界；OkHttp/Feign 会带入另一套客户端 | Apache HttpClient 5 异步客户端。每家独立客户端、连接池和单个 I/O reactor；连接上限来自 yml；池满等待共享 decisionTimeoutMs；销毁时关闭 | 取代 ADR-008 的传输选择，不改变 ModelAdapter 契约；依赖 httpclient5 |
| ADR-009 Prompt与配置版本 | 直接修改可变Prompt会污染运行中输入 | 草稿与不可变发布版本分离；RUNNING事务绑定Prompt/Config | 存储增加，便于关联行为；记录输入不保证模型再现同输出 |
| ADR-010 决策边界暂停 | 即时取消或暂存输出也合理，但费用与状态复杂 | AUTO/STEP与执行状态分离；当前决策完成后暂停；单步不累积；中止独立 | 控制变化只改调度与UI，不改规则；取代未确认的即时取消方案 |
| ADR-011 Envelope / Schema | 每游戏独立完整HTTP协议重复；LLM生成身份不可靠 | 平台Envelope + 游戏Payload；选择输出映射后走同一Submitter | Schema与动态规则均需校验；未来机器人只增游戏Schema/Codec |
| ADR-012 串行化与数据库幂等 | 只靠前端禁用/JVM锁不能保证重启正确 | 房间队列 + Room行锁 + CAS + 唯一键 + 持久回执，同事务状态/事件 | 短事务不等LLM；多实例需新增调度所有权，不声称现有队列已支持 |
| ADR-013 Stateless 与记录隔离 | 历史聊天/总结可丰富策略，但不需首版实现 | 当前合法观察构造Context；DecisionRecord与GameEvent分离；历史按viewer投影 | 保留MemoryPolicy契约，不建记忆服务；加入历史只扩ContextBuilder |
| ADR-014 快照与恢复 | 全Event Sourcing/自动续跑可行但增加实现和费用风险 | 每步快照+客观事件；重启取消未完成并暂停；已提交结果不重执行 | 小棋盘可承受；大状态再评估快照频率；没有自动模型重试 |
| ADR-015 本机实验室边界 | 完整账号系统超范围；无边界公开部署不可接受 | loopback、同源、Origin/Header校验、基本限流；Key仅后端 | 不是正式授权；远程开放前必须新增身份/权限/CSRF/SSRF方案 |

未来同步回合、远程协议和记忆模式为“提议/未来”，不是上述 ADR 自动批准的功能。正式采用时新增 ADR，说明取代关系，不重写历史理由。

## 2. 最关键的五个决定

1. GameDefinition/权威规则与 Runtime/模型完全分工，通用层不懂 column。
2. 决策开始时固定 Prompt/Config/Observation，暂停仅控制下一许可，中止另设命令。
3. 数据库原子结算、持久幂等回执和旧Match隔离保证动作至多执行一次。
4. Envelope由Runner构造，生成约束与本地/权威验证分层，不信任模型输出。
5. MySQL + MyBatis-Plus + 完整初始化SQL遵循用户选择，结构变更不自动迁移或覆盖数据。

## 3. 扩展验证矩阵

| 扩展 | 新增 | 需要修改 | 公共兼容与合理成本 |
| --- | --- | --- | --- |
| 第二款棋牌 | Definition、Codec、Schema、UI View | 注册装配、菜单 | Envelope不变；规则有自己的版本；TakeAway仅测试证明边界 |
| 机器人竞技 | 游戏State/Action/Observation和视图 | 游戏注册、输入规则说明 | 增Payload Schema；连续/实时控制可能需新调度，不能承诺现接口覆盖物理世界 |
| 四个Agent | 席位配置、多人UI | 当前单房间产品限制、人数校验、调度 | Participant集合及ID可复用；不是改两处常量即可发布 |
| 同时回合 | 收集器、阶段截止、批结算表/策略 | Scheduler、Settlement持久化实现及测试 | Envelope/Decision基准版本可复用；ACCEPTED≠EXECUTED，世界版本只在批结算推进 |
| 远程Agent | Pull/Push Adapter、授权/租约/SSRF边界 | 装配和管理UI | 标准DecisionRequest/Envelope可给Python SDK；不上传私有策略；future接口尚未部署 |
| 多模型厂商 | ModelAdapter/Capabilities | 服务端允许列表、配置UI | Runtime/规则不改；模型特性差异须显式验证 |
| Prompt A/B | 固定局势实验用例/调度与报告 | 新实验UI或离线工具 | PromptVersion/DecisionRecord复用；重调模型是新实验，不篡改回放 |
| RECENT_HISTORY | VisibleHistoryReader、上下文策略 | ContextBuilder/配置校验 | 不改GameEngine；事件投影权限必须测试 |
| STRATEGY_MEMORY | Memory adapter、版本/重置策略 | Runtime/ContextBuilder/记忆记录 | 不改GameDefinition，memoryUpdate非权威；跨局仍须范围确认 |
| 其他客户端 | CLI/SDK等 | 客户端授权入口 | REST/SSE/Envelope可复用；不依赖Java Entity |
| 多实例 | 调度所有权/恢复协调 | 房间队列、数据库竞争策略、部署 | 明确是后续架构工作；当前仅单实例 |

## 4. 最终架构审查的十个回答

| 问题 | 结论与限制 |
| --- | --- |
| 替换DeepSeek改哪里 | 新ModelAdapter、能力映射及配置允许列表；规则、调度、Prompt生命周期不改 |
| 四子棋换机器人动作 | 新GameDefinition/Codec/Schema及UI View；Envelope不改，实时性可能需新调度 |
| Python SDK能否复用 | 可以复用外部JSON契约；正式SDK与授权尚未实现，不声称现已接入 |
| 重复Action是否只一次 | 同ID回执、Decision唯一结算、席位阶段唯一结算、Room锁与CAS共同保证；需真实MySQL并发测试验收 |
| 推理中更新Prompt | 运行中持有旧版本；下次RUNNING原子绑定新版本 |
| 增有限历史是否改Engine | 不改，只扩ContextBuilder和可见历史读取；不能读取完整内部日志 |
| 增策略记忆是否改游戏 | 不改；记忆不能修改权威状态；需新增版本和重置策略 |
| 四人同时提交可复用什么 | Match/Participant/Turn/Decision、观察、Envelope、版本、回执原则；新增阶段收集/批结算 |
| 重启能否恢复 | 恢复已提交快照和回执，取消未完成决策并暂停；不自动重发模型 |
| 如何防Prompt/历史泄漏 | 管理面/审计/外部Agent投影分离，规则按viewer投影；不把实验室双方可见推广到竞技环境 |

## 5. 三个视角的批判性审查

| 视角与问题 | 风险 | 具体设计处理 | MVP必须解决 |
| --- | --- | --- | --- |
| Java架构师：抽象数量 | 多层空接口与大泛型图难审阅 | 单Maven工程；Parser可由Codec实现，Validator可由Definition实现，不一概建独立Bean | 是，控制实现尺度 |
| Java架构师：持久化污染 | MP注解/JsonNode流入所有规则 | Entity/DTO/领域分开；Codec只在适配边界；依赖规则测试 | 是 |
| Java架构师：Boot4依赖 | 老starter或Jackson2处理器编译/运行失败 | 锁Boot4专用starter、Jackson3 TypeHandler，阶段一解析依赖并测JSON | 是；本轮尚未执行依赖解析 |
| 游戏工程师：模式切换竞争 | 结算回调抢跑对方下一手 | 命令与tick同串行入口，tick重新校验模式/Match；手动Future测两种顺序 | 是 |
| 游戏工程师：幂等与崩溃 | 双响应重复落子、回执与棋盘分离 | 同事务回执/结算/快照/事件，唯一键、CAS、重启暂停 | 是 |
| 游戏工程师：未来同时回合 | 一动作一版本将来必重写公共协议 | resolve接收阶段集合；ACCEPTED与EXECUTED分开；未来仍需改收集与存储 | 当前边界要解决；完整批调度延后 |
| 独立开发者：数据库手工维护 | 既有库容易与完整SQL漂移 | 完整schema/data唯一来源、变更说明、人工精确调整及备份；不自动重跑 | 是；发布增多时复审维护策略 |
| 独立开发者：实验系统过度建设 | 为A/B和记忆提前建调度平台 | 只保证版本/记录可追溯；有限历史与策略记忆只定义契约 | 是，拒绝超范围实现 |
| 独立开发者：第二游戏成本 | 为“扩展性”再做一款产品 | 仅减石子测试夹具，不做UI或远程玩法 | 是，低成本证明无棋盘耦合 |
| 共同：模型别名与费用 | 配置可重建不等于输出可复现；取消仍计费 | 记录实际模型/时间/用量，无自动生成重试，限制单次调用 | 是；金额硬上限延后 |
| 共同：可见性与本机边界 | 私有审计或Key在未来外部接口泄露 | 独立投影和端点、Key不入记录；网络部署前补真实授权 | 当前投影要解决；正式账号延后 |

## 6. 尚未解决项与交付结论

没有需要重新选择的 MVP 产品语义冲突。以下是明确的实施验证门槛，而不是宣称已经通过：Boot/MP/Jackson3具体依赖解析、MySQL镜像补丁/摘要和DDL实测、DeepSeek真实受限调用、Mermaid图形渲染以及E2E。若验证失败，先报告具体证据和最小调整，不静默更换技术栈。

可延后决策：正式远程身份/租约、同步回合缺席规则、记忆写入冲突、多人UI、部署到网络、多实例调度所有权。当前协议保留相关身份和阶段边界，但没有把这些能力伪装成已实现。

当前方案适合熟悉Java的开发者按阶段审阅：一个规则实现、一个调度器、一个Runner、一个厂商HTTP适配器、一个MySQL库和一个实验室页面。最大必要复杂度来自可追溯的异步决策与事务一致性，而非预建通用Agent框架。
