# 参考项目研究与采用边界

> 核验日期：2026-10-19。仅通过公开文档与网页源码做只读研究，没有克隆、安装、运行或复制第三方实现。网页内容可能随分支更新；未取得可核验提交 SHA，不把 main/master 链接当永久快照。下文严格区分源码、官方描述和未确认能力。

## 1. 证据等级与项目概述

S=读到具体源码；D=官方产品/技术文档；R=仓库README描述（不等于功能实测）；?=现有资料未确认。未确认不表示不支持，不根据同名项目互相补全能力。

| 项目与来源 | 实际读到的内容 | 技术路线/使用场景 | 相关性与限制 |
| --- | --- | --- | --- |
| [OpenBMB AgentVerse](https://github.com/OpenBMB/AgentVerse) | S：[BasicEnvironment](https://github.com/OpenBMB/AgentVerse/blob/main/agentverse/environments/simulation_env/basic.py)、[BaseEnvironment](https://github.com/OpenBMB/AgentVerse/blob/main/agentverse/environments/base.py)；R：task-solving/simulation区分 | Python；自定义多Agent环境和任务协作 | 对规则/行动顺序/可见性最直接；不采用其运行环境和内存会话 |
| [Altaira Labs PromptArena](https://promptarena.altairalabs.ai/arena/) | D：场景、断言、Mock、会话记录、报告、用量和多供应商对比 | Go工具链与声明式测试场景；Prompt回归 | 对DecisionRecord和实验方法有帮助；未运行CLI，不能声称测试效果已复现 |
| [FrostLogic Agent Forge](https://github.com/frostlogic-ab/agent-forge) | R：Agent装饰器、LLM provider、tools、team、A2A、MCP及执行事件示例；src目录读取未成功，未核验具体实现类 | TypeScript；模型/工具/Agent组合 | 借鉴Runtime边界；文档能力不升级为源码实证，不复用框架 |
| [AgentForge/AgentForge](https://github.com/AgentForge/AgentForge) | R：Routine/Subroutine DAG、共享Context、Interface Factory；仓库简介为CareTaker/ConsensusBot相关开发 | 可组合认知子流程；内部运行机制 | 不与FrostLogic/JConradoN混同；本轮未逐项确认providers或本地模型实现 |
| [JConradoN/agentforge](https://github.com/JConradoN/agentforge) | R：local-first、YAML spec、Ollama、MockProvider、多个接入通道 | Python；自托管Agent运行；具体自评成绩未复现 | 支持“Agent内部流程由用户控制”的思路；不采用其反思/记忆/工具注册系统 |
| [Fetch.ai Agentverse](https://agentverse.ai/) / [官方本地Agent文档](https://docs.agentverse.ai/v-2/documentation/create-agents/local-agent-u-agent) | D：托管与本地运行区分；本地uAgent可在自有基础设施运行并接入协议 | Python uAgents及Agent身份/通信生态 | 借鉴运行位置与参与协议分离，不接入其网络 |
| [PromptArena.ai](https://promptarena.ai/) | D：官方首页检索内容描述Prompt分享、比较及社区参与；直接页面读取失败，没有实际登录验证 | 社区产品；技术实现未确认 | 只借鉴公开/私有策略和反馈体验；不把其宣传等同已验证评测体系 |
| [Mindclash Games](https://mindclashgames.com/) | D：桌游出版与作品目录；不是Agent框架，未逐款研读完整规则 | 桌面策略游戏产品 | 本任务提出的有限行动/资源竞争是设计借鉴方向，不声称官网已证明某种通用规则架构 |

Altaira 的官方页面含Go模块路径与相关PromptKit链接；另读到[PromptKit仓库](https://github.com/AltairaLabs/PromptKit)。未完整核验项目更名/模块版本沿革，不能将同名PromptArena.ai社区产品与其混为一体。

## 2. 源码观察：AgentVerse

BasicEnvironment 从 rule 配置抽取 order、visibility、selector、updater、describer；step 调用规则选择行动Agent和环境描述，以异步调用获得消息，再执行选择和可见性/记忆更新；reset 重置规则与Agent。该源码是对话环境，不是Frayground式数据库权威动作结算。

采用其“环境与Agent分离、规则决定顺序和可见性”的思想；不逐一复制五个规则插件。Frayground 先用单GameDefinition提供规则方法，TurnScheduler管理许可，GameObservation独立投影，避免让对话消息直接改变世界。源码存在Agent列表并不意味着已经解决了本项目的动作幂等、状态版本或数据库恢复。[源码依据](https://github.com/OpenBMB/AgentVerse/blob/main/agentverse/environments/simulation_env/basic.py)

## 3. 能力比较矩阵

下表的“有”仅表示相应等级资料出现该概念，不表示已进行兼容性或运行验收。社区/桌游资料与框架源码不使用同一强度的证据。

| 能力 | OpenBMB | Altaira | FrostLogic | AgentForge/AgentForge | JConradoN | Fetch.ai | PromptArena.ai | Mindclash |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Agent抽象 | S | D | R | R | R | D | ? | 非框架 |
| Environment抽象 | S | ? | ? | ? | ? | ? | ? | 非框架 |
| Rule抽象 | S | ? | ? | ? | ? | ? | ? | 游戏规则，接口未研究 |
| Turn/Step | S | D（场景轮次） | ? | R（子流程） | R（工作流） | ? | ? | 具体作品未研究 |
| Prompt管理 | R | D | R | ? | R | ? | D | 非框架 |
| 多模型 | R | D | R | R（资源接口） | R | ? | D（产品描述） | 非框架 |
| 外部Agent | ? | ? | R（A2A） | ? | R（HTTP） | D | ? | 非框架 |
| 消息通信 | S | D | R | R | R | D | ? | 非框架 |
| 记忆 | S（更新调用） | D（会话记录，非主观记忆） | R | R | R | ? | ? | 非框架 |
| 工具使用 | R | D | R | R（子过程） | R | D（协议示例） | ? | 非框架 |
| 决策/运行日志 | R | D | R | ? | R | D | ? | 非框架 |
| 权威游戏状态裁决 | ? | ? | ? | ? | ? | ? | ? | 由具体桌游规则，非服务端框架 |
| 策略测试/回归 | ? | D | ? | ? | R（自述测试） | ? | D（社区比较） | ? |

不能因看到“memory”“event”“JSON”就推定其具备本项目的私有可见性、事务审计或防重放机制。FrostLogic 的源码读取缺口保留为研究限制，不用README类名猜测具体接口实现。

## 4. 采用与舍弃

| 设计方法 | 解决的问题 / 来源 | 本项目落点 | MVP与更简单方式 |
| --- | --- | --- | --- |
| 环境/Agent/规则分离 | 防止模型实现侵入裁判；OpenBMB | GameDefinition / Runtime / Scheduler | 必须；先一个Definition，不拆五种插件 |
| 按角色可见的观察 | 隐藏信息与私有记录隔离；OpenBMB | observe / visibleHistory / DTO投影 | 必须；四子棋使用简单公开投影，隐藏游戏用测试证明 |
| 配置与场景可追溯 | 区分Prompt差异与采样随机；Altaira | PromptVersion / ConfigVersion / DecisionRecord | 必须记录；固定状态实验和报告调度延后 |
| Runtime供应商边界 | Agent可自主控制内部流程；FrostLogic、两种AgentForge | AgentRuntime / ModelAdapter | 必须；JDK HTTP足够，不集成工具编排 |
| 托管位置与协议分离 | 玩家无需上传完整Agent；Fetch.ai | 外部DecisionRequest/Envelope | 只设计Pull/Push，MVP无注册发现网络 |
| 策略所有权与反馈 | Prompt不只属于某局；PromptArena.ai | Agent级版本与管理面展示 | 必须数据边界；社区/排行榜延后 |
| 有限动作与确定性结算 | 简单规则承载策略；桌游方向 | 四子棋合法候选、明确规则 | 必须；未来资源游戏需单独验证，不扩展LLM裁判 |

不采用：Python核心规则运行时、认知DAG、共享全局聊天Context、自动反思、向量记忆、完整工具/MCP/A2A生态、Agent发现网络、社区与实验调度系统。理由是无当前闭环需要，且会模糊权威世界、私有策略与运行机制的边界；不是断言这些方法长期没有价值。

## 5. 许可与依赖风险

| 项目 | 当前可核验许可证据 | 维护/复用结论 |
| --- | --- | --- |
| OpenBMB | [LICENSE](https://github.com/OpenBMB/AgentVerse/blob/main/LICENSE)：Apache-2.0 | README有重构与旧稳定分支提示；当前维护活跃程度未精确核验，不依赖它运行 |
| Altaira相关PromptKit | [LICENSE](https://github.com/AltairaLabs/PromptKit/blob/main/LICENSE)：Apache-2.0 | 不将该许可自动覆盖另一个旧Go模块；若复用须核实具体仓库/tag及传递依赖 |
| FrostLogic | 仓库页面标注MIT；LICENSE正文读取未成功 | 只借鉴思想；未核验具体版本完整依赖树，不复制实现 |
| JConradoN | 仓库页面标注MIT；LICENSE正文读取未成功 | README测试/成绩为自述，未复现；不直接依赖 |
| AgentForge/AgentForge | 本轮未核验完整许可证 | 不复制代码、不作为依赖 |
| Fetch.ai服务/PromptArena.ai/Mindclash | 公开网站不等于全部实现开源 | 不依赖服务，不复制网站内容、素材或桌游规则文本 |

本轮没有建议直接复用第三方代码。若未来改变此决定，必须针对实际版本核验许可证文件、NOTICE、维护状态、运行时/传递依赖并记录理由；不能仅凭仓库名称、星数或网页宣传认定可直接引入。

## 6. 研究结论

对MVP最直接的帮助来自OpenBMB的规则/环境边界，以及Altaira的记录与比较方法。FrostLogic/AgentForge/Fetch.ai强调实现和部署方式的多样性，落实为小型Runtime契约即可；社区与桌游参考主要用于避免把产品等同于模型排行榜或单局胜负。

这些借鉴没有要求引入额外框架。当前最小证明是Mock四子棋完整对局、Prompt版本关联、原子幂等结算及仅测试注册的减石子规则。未来扩展的具体改动与成本见[架构验证矩阵](09-architecture-decisions.md)。
