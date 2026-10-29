# 四子棋（connect-four / 1）

规则以当前实现为准：[`ConnectFourDefinition`](../../backend/src/main/java/io/frayground/domain/game/connectfour/ConnectFourDefinition.java)、[`ConnectFourRules`](../../backend/src/main/java/io/frayground/domain/game/connectfour/ConnectFourRules.java)。领域类型草图见 [领域模型第 5 节](../03-domain-model.md)。

## 规则

- 标识：`GameKey("connect-four", "1")`。动作 schema：`connect-four/place-disc/1`。
- 棋盘 6 行 × 7 列。`row=0` 为顶，`column=0` 为最左。格子值为 `EMPTY` / `A` / `B`，不是 agentId。
- 恰好两个不同参与者，席位 `A`、`B`。`A` 先手。调度 `TURN_BASED_SEQUENTIAL`，每回合仅当前行动方。
- 唯一动作 `{"type":"place_disc","column":0..6}`。无过、无认输。
- 落子从 `row=5` 向上找该列第一个空位（重力）。列顶非空则满列。
- 校验按序返回第一个失败码：`UNKNOWN_PARTICIPANT`、`GAME_FINISHED`、`NOT_YOUR_TURN`、`INVALID_ACTION`、`COLUMN_OUT_OF_RANGE`、`COLUMN_FULL`。
- 胜负：以落子为中心扫描横、竖、两条斜线，任一方向连续 ≥4 即 `WIN`，原因 `FOUR_IN_A_ROW`（胜者名次 1，负者名次 2）。先判胜，再在 `moveCount=42` 且未胜时 `DRAW`，原因 `BOARD_FULL`（双方名次均为 1）。否则换手。终局 `currentParticipantId` 为 null，合法动作为空，禁止再 `resolve`。
- `winningCells` 收录所有长度 ≥4 的整段连线（可长于 4 格），按行、列排序。
- 观察为完全信息：双方都看到完整棋盘、己方席位、当前席位和手数。仅当前行动方收到未满列的 `legalColumns`。
- `ACTION_SELECTION` 候选局部 ID 为 `c0`..`c6`，只含未满列，例如 `c3 -> {type:place_disc,column:3}`。局部 ID 只在当前 Decision 内有效。
- 成功事件：`DiscPlaced`（`row`、`column`、`seat`、`participantId`）。终局另有 `MatchFinished`（`result`、`winningCells`）。`visibleHistory` 只投影这两类事件的上述字段。

状态不变量（构造与快照解码均拒绝违反者）：无悬浮子；`A` 的子数等于 `B` 或恰好多 1；双方不能同时成线；胜方必须是刚落子的一方；进行中时当前行动方与手数一致；终局当前行动方为空且 `winningCells` 与结果一致。

## 实现要点

- 领域：`ConnectFourDefinition` 负责初始化、回合、观察、候选、校验、结算与可见历史；`ConnectFourRules` 负责连线、胜负与 `check`；`ConnectFourState` 在构造时调用 `check`。非法动作抛错，不修改旧状态；新棋盘与集合防御性复制。
- 静态 Schema 与动态规则分离。[`ConnectFourActionSchemaProvider`](../../backend/src/main/java/io/frayground/adapter/game/connectfour/ConnectFourActionSchemaProvider.java) 只约束 `type=place_disc` 与 `column` 整数 0..6、禁止多余字段。满列、回合和终局由 `validate` 判断。
- [`ConnectFourCodec`](../../backend/src/main/java/io/frayground/adapter/game/connectfour/ConnectFourCodec.java) 编解码快照，并核对棋盘、落子数量、席位、当前行动方、胜线和结果，不推测缺失历史。
- [`ConnectFourGameConfiguration`](../../backend/src/main/java/io/frayground/adapter/game/connectfour/ConnectFourGameConfiguration.java) 把 definition、codec、schema 组成 `GameRegistration`，按完整 `GameKey` 注册，不做版本回退。
- [`ConnectFourDecisionMetadata`](../../backend/src/main/java/io/frayground/adapter/game/connectfour/ConnectFourDecisionMetadata.java) 提供同一 `GameKey`，供决策元数据使用。

## 配置项

只有注册开关，规则参数不可配。

| 项 | 位置 | 行为 |
| --- | --- | --- |
| `frayground.games.connect-four.enabled` | [`application.yml`](../../backend/src/main/resources/application.yml) 当前为 `true` | `ConnectFourGameConfiguration` 要求值为 `true`，`matchIfMissing=false`。缺省或 `false` 不注册 definition、codec、schema。修改后需重启。 |

行数 6、列数 7、连珠长度 4、先手席位 `A`、唯一动作 `place_disc` 写死在领域代码与 JSON Schema 中，没有对应配置项。
