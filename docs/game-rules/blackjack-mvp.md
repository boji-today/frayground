# Blackjack MVP（blackjack / 1）

> 状态：已确认设计，尚未实现。本版本验证共享随机资源、严格顺序决策、隐藏信息和系统自动推进；不是完整赌场牌桌或多轮赛事。

## 范围

- `GameKey("blackjack", "1")`。一个 Match 对应一手完整 Blackjack。
- 每局 1–3 名 AI Participant，共同对抗一名系统庄家。庄家不是 Participant，不绑定 Agent，也不创建 Turn 或 Decision。
- 每个 Match 使用一套新建的 6 副牌 Shoe；不跨 Match 复用。
- 每名玩家固定下注 1 个虚拟单位。
- 仅支持 `HIT`、`STAND`；不支持分牌、加倍、保险、投降、弃牌、自定义下注或资金账户。
- 不支持 Match 内多 Round、累计赛事、跨 Match 统计、座位轮换或并行行动。
- 主动弃赛、`FORFEITED`、退出顺序排名及跨 Round 资格管理不属于本版。当前手牌也不提供代替弃赛的动作。

## 牌与点数

- 6 副标准 52 张牌，共 312 张实体牌，不含 Joker。
- 实体牌在权威状态中以 `deckNo + suit + rank` 唯一标识；玩家观察和公开事件只显示 `suit + rank`。
- `2`–`10` 按牌面计分，`J/Q/K` 为 10，`A` 在不爆牌时按 11，否则按 1。
- 自然 Blackjack 仅指初始两张牌由一张 `A` 和一张 10 点牌组成；后续 HIT 得到的 21 点不是自然 Blackjack。

## 初始化与发牌

1. Match 创建时处于 `READY`，尚未洗牌或发牌，也没有 Participant Turn。
2. start 或 step 触发 GameEngine 的系统初始化结算：
   - 平台生成随机 seed，按固定算法版本洗牌；
   - 按固定座位顺序 `P1 → P2 → P3 → Dealer` 发第一张，再按相同顺序发第二张；
   - 庄家第一张为明牌，第二张为暗牌；
   - 记录洗牌审计信息和每次实际发牌事件；
   - 整个初始化只推进一次 `stateVersion`。
3. 庄家立即检查自然 Blackjack。庄家自然时不创建任何玩家 Decision，直接结算：玩家自然为 PUSH，其余玩家 LOSS。
4. 庄家无自然时，玩家自然 Blackjack 自动结束行动，不创建 Decision。若没有任何玩家需要行动，初始化结算直接结束 Match；否则为第一名可行动玩家创建 Turn。

算法版本与 seed 只用于内部审计，不用于重新洗牌完成测试或回放。测试和回放以已记录的实际发牌事件为准。完整牌序和当前游标属于权威私有状态，不进入 Agent 观察或公开事件。

## 玩家行动

- 调度为 `TURN_BASED_SEQUENTIAL`，按固定座位顺序行动。
- 当前玩家 `HIT` 后：
  - 小于 21 点：仍由该玩家继续行动；
  - 等于 21 点：自动结束该玩家行动；
  - 超过 21 点：该玩家 BUST，自动结束行动。
- 当前玩家 `STAND` 后结束行动。
- natural、STAND、21 点或 BUST 的玩家不再获得 Turn。
- 当前玩家结束后，选择下一名可行动玩家；不存在可行动玩家时进入庄家阶段。
- Action Schema 只接受 `{"type":"hit"}` 或 `{"type":"stand"}`，禁止多余字段。动态合法动作由当前状态决定。

## 庄家自动行动

- 最后一名玩家的动作先完成一次 Participant 结算并推进一次 `stateVersion`。
- 随后 GameEngine 执行一次独立的 SYSTEM 结算；该结算没有 Participant、Turn、Decision 或 Agent Action。
- SYSTEM 结算内依次记录独立事件：揭示暗牌、每次庄家抽牌、庄家停牌及最终结算；整段庄家流程合计只再推进一次 `stateVersion`。
- 庄家按 S17：小于 17 必须 HIT，所有 17（包括 soft 17）及以上必须 STAND。
- 如果所有玩家都已 BUST，庄家仍揭示暗牌，但不继续抽牌，直接结算。
- 自动流程不可作为 pause/step 截断点，必须推进到下一个 AI Decision 边界或 Match 终局。STEP 仍表示一个 AI 决策及其必需的系统自动结算。

## 玩家结算

- 庄家自然 Blackjack：
  - 玩家自然 Blackjack：净收益 `0`；
  - 其他玩家：净收益 `-1`。
- 庄家无自然 Blackjack：
  - 玩家自然 Blackjack：净收益 `+1.5`；
  - 玩家 BUST：净收益 `-1`；
  - 庄家 BUST 且玩家未 BUST：净收益 `+1`；
  - 双方均未 BUST：玩家点数较高为 `+1`，较低为 `-1`，相同为 `0`。

个人 WIN / PUSH / LOSS 由 `ParticipantStanding.score` 的正数、零、负数推导，不新增个人结果字段。

## 排名与 Match 结果

- 所有 Participant 按净收益降序生成名次，采用竞赛排名：例如 `1, 1, 3`。
- 相同净收益并列；即使所有玩家均为 `-1`，也全部并列第 1。
- 个人对庄家的结果与玩家间相对名次是两个概念。
- 仅当存在唯一 `rank=1` 且该玩家净收益大于 0 时，`GameResult.status=WIN`。
- 并列第一、唯一第一但净收益为 0、以及所有玩家净收益均为负数时，`GameResult.status=DRAW`；仍保留完整 standings。
- Match 正常结算后为 `FINISHED`。本版不增加新的 `GameOutcome`。

这要求通用结果语义解释为：rank 只表示相对名次；只有 `GameResult.status=WIN` 时，唯一 rank 1 才是 Match 胜者。现有“rank 1 恒为 winner、允许并列 winner”的注释不适用于本规则，实施时需同步修正文档语义，但不需要扩展结果数据结构。

## 可见性

- 玩家可见：所有玩家已经发出的牌、各玩家当前状态、庄家明牌、已公开的庄家牌、当前行动席位及本局有序公开动作摘要。
- 后位玩家可观察前位玩家的 HIT / STAND、已公开手牌及结果状态。
- 玩家不可见：庄家暗牌牌面、未来牌序、Shoe 游标、seed 和其他内部审计数据。
- 权威暗牌发牌事件保存真实实体牌；玩家侧历史只投影“庄家已收到暗牌”的占位事件。`DealerHoleRevealed` 后才公开牌面。
- `BlackjackObservation` 直接携带有序公开动作摘要，以符合当前 STATELESS Decision Context；不依赖默认历史记忆。
- Match 终局后庄家暗牌保持公开，未发出的 Shoe 仍不公开。

## 事件

至少记录以下客观事件；同一结算内按发生顺序分配独立 sequence：

- `ShoeShuffled`：算法版本和 seed，仅内部可见；
- `CardDealt`：实际发出的实体牌，庄家暗牌按受众过滤；
- `PlayerHit`、`PlayerStand`、`PlayerReached21`、`PlayerBust`；
- `DealerBlackjackChecked`；
- `DealerHoleRevealed`、`DealerHit`、`DealerStand`；
- `MatchFinished`。

回放使用事件中的实际发牌事实，不根据 seed 重新生成牌序。未来牌序不能通过事件读取接口泄露。

## 领域对象与接口兼容性

可直接复用：

- Room 跨 Match 存活，Match 表示一手完整游戏；
- Match 的 Participant 列表支持 1–3 个席位，庄家保留在 BlackjackState 内；
- 顺序 Turn 可表达同一玩家连续 HIT 及按座位换手；
- `observe(state, participantId)` 可产生席位级隐藏信息投影；
- `ParticipantStanding(rank, score)` 可表达净收益和并列排名；
- 权威快照可保存有限 Shoe 的完整牌序与游标。

需要最小扩展：

1. `GameDefinition` 增加无 Participant 的强制自动转换能力，供初始化发牌和庄家阶段使用；普通 Agent Action 仍走 `validate/resolve`。
2. 调度器在 start、step 或 Participant 结算后自动执行强制转换，直到下一个 Decision 边界或终局，并设置循环上限防止错误规则无限推进。
3. `InitializedMatch` 与 MatchFactory 允许 READY Match 暂无首个 Turn；首个 Turn 由初始化 SYSTEM 结算后创建。
4. Settlement 增加 `PARTICIPANT_ACTION` / `SYSTEM` 原因；SYSTEM 允许没有 turnId 和 SettledAction。持久化结构必须能保存无 participant/decision/action 的系统结算。
5. 系统结算同样保存快照、事件和版本；GameEngine 只计算规则转换，事务编排层负责原子持久化。
6. 结果结构不扩展，但需把 rank 的通用语义从“恒为 winner”修正为“相对名次”；WIN 的胜者由具体游戏终局规则确定。
7. 读取 Agent 历史或公开牌桌历史时必须经过游戏可见性投影，不能返回原始权威事件或编码后的权威 State。

单个参与者 FORFEITED 后其他人继续的能力在本版不实现，也不通过 ABORTED、CANCELLED、LOSS 或隐藏动作模拟；待多 Round 赛事进入范围后单独设计。

## BlackjackState 建议内容

- 阶段：`READY`、`PLAYER_TURNS`、`DEALER_TURN`、`FINISHED`；
- 固定 Participant 顺序及当前行动 Participant；
- 312 张实体牌的私有有序列表和下一张游标；
- 每名玩家的手牌、点数、natural/BUST/STAND 状态；
- 庄家手牌、暗牌可见状态及点数；
- 有序公开动作摘要；
- 当前 `GameResult`。

Shoe 和 Dealer 只作为 BlackjackState 的游戏专用组成，不新增通用领域实体。

## 配置项

仅提供注册开关 `frayground.games.blackjack.enabled`，缺省不注册，修改后重启生效。

6 副牌、S17、1–3 名玩家、固定下注 1、自然 Blackjack 3:2、动作集合和发牌顺序均固定在规则版本 `1` 中，不提供运行时配置。
